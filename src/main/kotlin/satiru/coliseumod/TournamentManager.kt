package satiru.coliseumod

import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.ClickEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

class TournamentManager(private val config: ColiseuConfig) {

    enum class Estado { IDLE, FILA, ARENA }

    /** Uma luta em andamento (ou em contagem para começar). [arena] é o índice em config.arenas. */
    private class Luta(val a: UUID, val b: UUID, val arena: Int, var iniciaEmMs: Long) {
        var comecou = false
        var inicioMs = 0L
        var tentativas = 0
        var resolvida = false
    }

    var estado = Estado.IDLE
        private set

    /** Sorteio e entrega de prêmios (também usado pelos comandos de admin). */
    val recompensas = RewardService()

    private var servidor: MinecraftServer? = null
    private val retornos = RetornoStore()
    private val nomes = mutableMapOf<UUID, String>()

    // fila
    private val jogadores = LinkedHashSet<UUID>()
    private val avisosFeitos = mutableSetOf<Int>()
    private var proximaAberturaMs = 0L
    private var fechaEmMs = 0L
    /** Regra de lendários sorteada para o torneio atual (-1 = liberado). */
    private var limiteAtual = -1

    // torneio
    private val vivos = LinkedHashSet<UUID>()
    private val eliminados = mutableListOf<UUID>()      // na ordem em que caíram
    private val teveBye = mutableSetOf<UUID>()
    private val filaLutas = mutableListOf<Pair<UUID, UUID>>()
    private val lutas = mutableListOf<Luta>()           // lutas acontecendo agora (uma por arena)
    private var rodada = 0
    private var proximaLutaEmMs = 0L
    private var arenaTerminaEmMs = 0L

    // ---------- ciclo de vida ----------

    fun iniciar(server: MinecraftServer) {
        servidor = server
        agendarProximo()
        ColiseuMod.LOGGER.info("Coliseu: first queue opens in ${config.intervaloMinutos} min")
    }

    /** Chamado todo tick; só trabalha 1x por segundo. */
    fun tick(server: MinecraftServer) {
        if (server.ticks % 20 != 0) return
        val agora = System.currentTimeMillis()

        when (estado) {
            Estado.IDLE -> if (agora >= proximaAberturaMs) abrirFila(server)

            Estado.FILA -> {
                val restante = ceil((fechaEmMs - agora) / 1000.0).toInt()
                if (restante <= 0) {
                    fecharFila(server)
                } else {
                    config.avisosEmSegundos.sortedDescending().forEach { aviso ->
                        if (restante <= aviso && avisosFeitos.add(aviso)) {
                            anunciar(server, restante)
                        }
                    }
                }
            }

            Estado.ARENA -> tickArena(server, agora)
        }
    }

    private fun tickArena(server: MinecraftServer, agora: Long) {
        if (vivos.isEmpty()) {
            encerrar(server, Msg.t("tournament.all_left"))
            return
        }
        if (agora >= arenaTerminaEmMs) {
            encerrar(server, Msg.t("tournament.stalled"))
            return
        }

        // acompanha as lutas em andamento
        lutas.toList().forEach { l ->
            if (l.resolvida) return@forEach
            if (!l.comecou) {
                if (agora >= l.iniciaEmMs) comecarBatalha(server, l)
            } else if (agora - l.inicioMs > config.tempoBatalhaSegundos * 1000L) {
                // luta demorou demais: encerra e sorteia o vencedor
                BatalhaCobblemon.encerrarBatalhaDe(l.a)
                if (l.resolvida || l !in lutas) return@forEach
                val vencedor = if (Random.nextBoolean()) l.a else l.b
                aviso(server, Msg.t("fight.timeout", "player" to nome(vencedor)), Formatting.YELLOW)
                resolverLuta(server, l, vencedor)
            }
        }

        if (agora < proximaLutaEmMs) return

        // rodada acabou (nenhuma luta pendente nem em andamento): sorteia a próxima
        if (filaLutas.isEmpty() && lutas.isEmpty()) {
            proximaRodada(server)
            return
        }

        // preenche as arenas livres com as próximas lutas da rodada
        while (filaLutas.isNotEmpty()) {
            val arena = arenaLivre() ?: break
            prepararLuta(server, arena)
        }
    }

    // ---------- ações dos jogadores ----------

    /** Retorna uma mensagem de erro, ou null se deu certo. */
    fun entrar(player: ServerPlayerEntity): String? {
        if (estado == Estado.ARENA) return Msg.t("err.tournament_running")
        if (estado != Estado.FILA) return Msg.t("err.no_queue")
        if (player.uuid in jogadores) return Msg.t("err.already_in_queue")
        if (jogadores.size >= config.maxJogadores) return Msg.t("err.queue_full")
        if (!player.isAlive) return Msg.t("err.dead")
        if (BatalhaCobblemon.emBatalha(player)) return Msg.t("err.in_battle")
        BatalhaCobblemon.validarTime(player, config, limiteAtual)?.let { return it }

        jogadores.add(player.uuid)
        nomes[player.uuid] = player.name.string
        servidor?.let {
            aviso(
                it,
                Msg.t("queue.joined", "player" to player.name.string, "count" to jogadores.size, "max" to config.maxJogadores),
                Formatting.GREEN
            )
        }
        return null
    }

    /** Sai da fila ou desiste do torneio. Retorna true se o jogador estava em algum dos dois. */
    fun sair(server: MinecraftServer, player: ServerPlayerEntity): Boolean {
        if (jogadores.remove(player.uuid)) {
            aviso(
                server,
                Msg.t("queue.left", "player" to player.name.string, "count" to jogadores.size, "max" to config.maxJogadores),
                Formatting.GRAY
            )
            return true
        }
        return desistir(server, player.uuid)
    }

    /** Desconexão: sai da fila; se estava no torneio, perde. A posição original fica em disco. */
    fun aoDesconectar(uuid: UUID) {
        jogadores.remove(uuid)
        val server = servidor ?: return
        desistir(server, uuid)
    }

    /** Entrou no servidor: entrega prêmios pendentes e, se ficou preso na arena, manda de volta. */
    fun aoEntrar(server: MinecraftServer, player: ServerPlayerEntity) {
        recompensas.entregarPendentes(server, player)

        if (!retornos.contem(player.uuid)) return
        if (estado == Estado.ARENA && player.uuid in vivos) return
        server.execute {
            retornar(server, player)
            player.sendMessage(Text.literal(Msg.t("player.returned")).formatted(Formatting.YELLOW), false)
        }
    }

    // ---------- eventos do Cobblemon ----------

    /** Fim de uma batalha (vitória/derrota). Chamado pelo evento BATTLE_VICTORY. */
    fun aoFimDeBatalha(vencedores: List<UUID>, perdedores: List<UUID>) {
        val server = servidor ?: return
        server.execute {
            val todos = vencedores + perdedores
            val l = lutas.firstOrNull {
                it.comecou && !it.resolvida && (it.a in todos || it.b in todos)
            } ?: return@execute

            val vencedor = vencedores.firstOrNull { it == l.a || it == l.b }
            if (vencedor != null) {
                resolverLuta(server, l, vencedor)
            } else {
                val sorteado = if (Random.nextBoolean()) l.a else l.b
                aviso(server, Msg.t("fight.draw", "player" to nome(sorteado)), Formatting.YELLOW)
                resolverLuta(server, l, sorteado)
            }
        }
    }

    /** Jogador fugiu da batalha. Chamado pelo evento BATTLE_FLED. */
    fun aoFugir(uuid: UUID) {
        val server = servidor ?: return
        server.execute { desistir(server, uuid) }
    }

    // ---------- ações de admin ----------

    fun abrirAgora(server: MinecraftServer): Boolean {
        if (estado != Estado.IDLE) return false
        abrirFila(server)
        return true
    }

    fun cancelar(server: MinecraftServer): Boolean {
        if (estado != Estado.FILA) return false
        aviso(server, Msg.t("queue.cancelled_admin"), Formatting.RED)
        resetar()
        return true
    }

    fun encerrarAdmin(server: MinecraftServer): Boolean {
        if (estado != Estado.ARENA) return false
        encerrar(server, Msg.t("tournament.ended_admin"))
        return true
    }

    /**
     * Relê colosseum.json, colosseum_loot.json e o idioma.
     * Retorna false se algum arquivo estiver com erro (nesse caso nada é alterado).
     */
    fun recarregar(): Boolean {
        val nova = ColiseuConfig.tentarCarregar() ?: return false
        if (!recompensas.recarregar()) return false
        config.aplicar(nova)
        Msg.carregar(config.idioma)
        if (estado == Estado.IDLE) agendarProximo()
        return true
    }

    fun definirLobby(player: ServerPlayerEntity): PosicaoConfig {
        val pos = Teleporte.posicaoAtual(player)
        config.lobby = pos
        config.salvar()
        return pos
    }

    /** Define a posição [lado] (1 ou 2) da arena [numero] (começa em 1). */
    fun definirArena(player: ServerPlayerEntity, numero: Int, lado: Int): PosicaoConfig {
        val pos = Teleporte.posicaoAtual(player)
        val arena = config.arena(numero)
        if (lado == 1) arena.pos1 = pos else arena.pos2 = pos
        config.salvar()
        return pos
    }

    /** Resumo da configuração do coliseu, uma linha por item. */
    fun descreverArenas(): List<String> {
        val linhas = mutableListOf<String>()
        linhas.add(if (config.lobby != null) Msg.t("admin.lobby_ok") else Msg.t("admin.lobby_missing"))
        if (config.arenas.isEmpty()) {
            linhas.add(Msg.t("admin.no_arenas"))
        }
        config.arenas.forEachIndexed { i, arena ->
            val p1 = if (arena.pos1 != null) Msg.t("admin.ok") else Msg.t("admin.missing")
            val p2 = if (arena.pos2 != null) Msg.t("admin.ok") else Msg.t("admin.missing")
            val nota = if (arena.completa) "" else Msg.t("admin.incomplete")
            linhas.add(Msg.t("admin.arena_line", "n" to (i + 1), "p1" to p1, "p2" to p2, "note" to nota))
        }
        linhas.add(Msg.t("admin.usable", "n" to arenasCompletas().size))
        return linhas
    }

    fun statusTexto(): String {
        val minutos = ceil((proximaAberturaMs - System.currentTimeMillis()) / 60000.0).toInt().coerceAtLeast(0)
        return when (estado) {
            Estado.FILA -> Msg.t(
                "status.queue",
                "count" to jogadores.size,
                "max" to config.maxJogadores,
                "rule" to textoRegra(limiteAtual)
            )
            Estado.ARENA -> Msg.t("status.arena", "count" to vivos.size, "round" to rodada)
            Estado.IDLE -> Msg.t("status.idle", "min" to minutos)
        }
    }

    // ---------- fila ----------

    private fun abrirFila(server: MinecraftServer) {
        estado = Estado.FILA
        jogadores.clear()
        avisosFeitos.clear()
        limiteAtual = config.opcoesLimitados.random()
        fechaEmMs = System.currentTimeMillis() + config.filaSegundos * 1000L
        // avisos que já "passaram" (ex.: 60s quando a fila dura 60s) não repetem
        config.avisosEmSegundos.filter { it >= config.filaSegundos }.forEach { avisosFeitos.add(it) }

        recompensas.avisarAdmins(server)

        // a regra é a primeira coisa que aparece no chat
        aviso(server, Msg.t("rule.title", "rule" to textoRegra(limiteAtual)), Formatting.LIGHT_PURPLE)
        anunciar(server, config.filaSegundos)
    }

    private fun fecharFila(server: MinecraftServer) {
        // revalida: o jogador pode ter trocado de time ou entrado em batalha desde que entrou na fila
        val online = jogadores
            .mapNotNull { server.playerManager.getPlayer(it) }
            .filter { p ->
                val erro = when {
                    !p.isAlive -> Msg.t("queue.reason_dead")
                    BatalhaCobblemon.emBatalha(p) -> Msg.t("queue.reason_battle")
                    else -> BatalhaCobblemon.validarTime(p, config, limiteAtual)
                }
                if (erro != null) {
                    p.sendMessage(
                        Text.literal(Msg.t("queue.removed", "reason" to erro)).formatted(Formatting.RED),
                        false
                    )
                }
                erro == null
            }

        if (online.size < config.minJogadores) {
            aviso(
                server,
                Msg.t("queue.cancel_min", "min" to config.minJogadores, "count" to online.size),
                Formatting.RED
            )
            resetar()
            return
        }

        val lobby = config.lobby
        if (lobby == null || arenasCompletas().isEmpty()) {
            aviso(server, Msg.t("queue.cancel_not_configured"), Formatting.RED)
            ColiseuMod.LOGGER.warn("Coliseu: set up the lobby and at least one arena (see /colosseum help).")
            resetar()
            return
        }

        vivos.clear()
        eliminados.clear()
        teveBye.clear()
        filaLutas.clear()
        lutas.clear()
        rodada = 0

        online.forEach { p ->
            nomes[p.uuid] = p.name.string
            retornos.guardar(p.uuid, Teleporte.posicaoAtual(p))
            if (Teleporte.levar(server, p, lobby)) {
                vivos.add(p.uuid)
                BatalhaCobblemon.curar(p)
            } else {
                retornos.remover(p.uuid)
                p.sendMessage(Text.literal(Msg.t("queue.teleport_fail")).formatted(Formatting.RED), false)
            }
        }

        if (vivos.size < config.minJogadores) {
            encerrar(server, Msg.t("queue.cancel_teleport"))
            return
        }

        jogadores.clear()
        estado = Estado.ARENA
        val agora = System.currentTimeMillis()
        proximaLutaEmMs = agora + 5000
        arenaTerminaEmMs = agora + config.tempoMaximoSegundos * 1000L
        aviso(server, Msg.t("tournament.started", "count" to vivos.size), Formatting.GREEN)
        aviso(server, Msg.t("rule.short", "rule" to textoRegra(limiteAtual)), Formatting.LIGHT_PURPLE)
        ColiseuMod.LOGGER.info("Coliseu: start with ${vivos.map { nome(it) }} | rule: ${textoRegra(limiteAtual)}")
    }

    // ---------- chaveamento ----------

    /** Índices das arenas que têm as duas posições definidas. */
    private fun arenasCompletas(): List<Int> =
        config.arenas.withIndex().filter { it.value.completa }.map { it.index }

    /** Primeira arena completa que não está sendo usada por nenhuma luta. */
    private fun arenaLivre(): Int? =
        arenasCompletas().firstOrNull { idx -> lutas.none { it.arena == idx } }

    private fun proximaRodada(server: MinecraftServer) {
        if (vivos.size <= 1) {
            finalizar(server)
            return
        }

        rodada++
        val ordem = vivos.toMutableList().apply { shuffle() }

        // número ímpar: um jogador espera (bye) e avança sem lutar nesta rodada
        var nomeBye: String? = null
        if (ordem.size % 2 == 1) {
            val candidatos = ordem.filter { it !in teveBye }.ifEmpty { ordem }
            val escolhido = candidatos.random()
            teveBye.add(escolhido)
            ordem.remove(escolhido)
            nomeBye = nome(escolhido)
        }

        filaLutas.clear()
        ordem.chunked(2).forEach { filaLutas.add(it[0] to it[1]) }

        val titulo = if (vivos.size == 2) Msg.t("round.final") else Msg.t("round.title", "n" to rodada)
        aviso(
            server,
            Msg.t("round.summary", "title" to titulo, "fights" to filaLutas.size, "arenas" to arenasCompletas().size),
            Formatting.AQUA
        )
        if (nomeBye != null) {
            aviso(server, Msg.t("round.bye", "player" to nomeBye), Formatting.AQUA)
        }

        val agora = System.currentTimeMillis()
        proximaLutaEmMs = agora + 3000
        arenaTerminaEmMs = agora + config.tempoMaximoSegundos * 1000L
    }

    private fun prepararLuta(server: MinecraftServer, arena: Int) {
        val (a, b) = filaLutas.removeAt(0)
        val pa = server.playerManager.getPlayer(a)
        val pb = server.playerManager.getPlayer(b)

        // alguém caiu entre o sorteio e a luta: quem ficou avança
        if (pa == null || pb == null) {
            if (pa == null) eliminar(server, a)
            if (pb == null) eliminar(server, b)
            return
        }

        val cfgArena = config.arenas.getOrNull(arena)
        val pos1 = cfgArena?.pos1
        val pos2 = cfgArena?.pos2
        if (pos1 == null || pos2 == null) {
            encerrar(server, Msg.t("tournament.arena_missing_pos", "n" to (arena + 1)))
            return
        }

        // garante que ninguém está preso em outra batalha (ex.: Pokémon selvagem)
        BatalhaCobblemon.encerrarBatalhaDe(a)
        BatalhaCobblemon.encerrarBatalhaDe(b)
        BatalhaCobblemon.curar(pa)
        BatalhaCobblemon.curar(pb)
        Teleporte.levar(server, pa, pos1)
        Teleporte.levar(server, pb, pos2)

        val agora = System.currentTimeMillis()
        val luta = Luta(a, b, arena, agora + config.contagemLutaSegundos * 1000L)
        lutas.add(luta)
        arenaTerminaEmMs = agora + config.tempoMaximoSegundos * 1000L

        val quando = if (config.contagemLutaSegundos <= 0) {
            Msg.t("fight.now")
        } else {
            Msg.t("fight.in", "n" to config.contagemLutaSegundos)
        }
        aviso(
            server,
            Msg.t("fight.announce", "arena" to (arena + 1), "a" to nome(a), "b" to nome(b), "when" to quando),
            Formatting.GOLD
        )

        // contagem 0: a batalha começa no mesmo instante do teleporte, sem dar tempo de sair da posição
        if (config.contagemLutaSegundos <= 0) comecarBatalha(server, luta)
    }

    private fun comecarBatalha(server: MinecraftServer, l: Luta) {
        val pa = server.playerManager.getPlayer(l.a)
        val pb = server.playerManager.getPlayer(l.b)
        if (pa == null || pb == null) {
            // alguém caiu na contagem (normalmente o aoDesconectar já resolveu antes)
            resolverLuta(server, l, if (pa != null) l.a else l.b)
            return
        }

        if (BatalhaCobblemon.iniciarBatalha(pa, pb, config.nivelBatalha)) {
            val agora = System.currentTimeMillis()
            l.comecou = true
            l.inicioMs = agora
            arenaTerminaEmMs = agora + config.tempoMaximoSegundos * 1000L
            return
        }

        l.tentativas++
        if (l.tentativas >= 3) {
            val sorteado = if (Random.nextBoolean()) l.a else l.b
            aviso(server, Msg.t("fight.start_failed", "player" to nome(sorteado)), Formatting.RED)
            ColiseuMod.LOGGER.error("Coliseu: failed to start battle ${nome(l.a)} vs ${nome(l.b)} after 3 attempts")
            resolverLuta(server, l, sorteado)
        } else {
            l.iniciaEmMs = System.currentTimeMillis() + 1500
        }
    }

    /** Fecha uma luta: vencedor volta ao lobby, perdedor é eliminado e vai para casa. */
    private fun resolverLuta(server: MinecraftServer, l: Luta, vencedor: UUID) {
        if (l.resolvida) return
        l.resolvida = true
        lutas.remove(l)

        val perdedor = if (vencedor == l.a) l.b else l.a
        val agora = System.currentTimeMillis()
        proximaLutaEmMs = agora + config.pausaEntreLutasSegundos * 1000L
        arenaTerminaEmMs = agora + config.tempoMaximoSegundos * 1000L

        aviso(server, Msg.t("fight.won", "winner" to nome(vencedor), "loser" to nome(perdedor)), Formatting.GREEN)

        server.playerManager.getPlayer(vencedor)?.let { p ->
            BatalhaCobblemon.curar(p)
            config.lobby?.let { Teleporte.levar(server, p, it) }
        }
        eliminar(server, perdedor)
    }

    /** Tira o jogador do torneio, cura o time dele e o devolve ao lugar de origem. */
    private fun eliminar(server: MinecraftServer, uuid: UUID) {
        if (!vivos.remove(uuid)) return
        eliminados.add(uuid)
        filaLutas.removeAll { it.first == uuid || it.second == uuid }
        server.playerManager.getPlayer(uuid)?.let { p ->
            BatalhaCobblemon.curar(p)
            retornar(server, p)
            p.sendMessage(Text.literal(Msg.t("fight.eliminated")).formatted(Formatting.YELLOW), false)
        }
    }

    /** Desistência, fuga ou desconexão. Retorna true se o jogador estava no torneio. */
    private fun desistir(server: MinecraftServer, uuid: UUID): Boolean {
        if (estado != Estado.ARENA || uuid !in vivos) return false

        val l = lutas.firstOrNull { !it.resolvida && (it.a == uuid || it.b == uuid) }
        if (l != null) {
            val vencedor = if (l.a == uuid) l.b else l.a
            BatalhaCobblemon.encerrarBatalhaDe(uuid)
            resolverLuta(server, l, vencedor)
        } else {
            eliminar(server, uuid)
        }
        return true
    }

    private fun finalizar(server: MinecraftServer) {
        val campeao = vivos.firstOrNull()
        val colocacao = listOfNotNull(campeao) + eliminados.reversed()

        aviso(server, Msg.t("podium.header"), Formatting.GOLD)
        colocacao.take(3).forEachIndexed { i, uuid ->
            val rotulo = when (i) {
                0 -> Msg.t("podium.first")
                1 -> Msg.t("podium.second")
                else -> Msg.t("podium.third")
            }
            aviso(
                server,
                Msg.t("podium.line", "label" to rotulo, "player" to nome(uuid)),
                if (i == 0) Formatting.GOLD else Formatting.YELLOW
            )
        }

        // recompensas: sorteio na loot pool, anúncio no chat e entrega
        recompensas.entregarPodio(server, colocacao) { nome(it) }

        ColiseuMod.LOGGER.info("Coliseu: final standings ${colocacao.map { nome(it) }}")
        encerrar(server, Msg.t("tournament.thanks"))
    }

    // ---------- encerramento ----------

    /** Cura e devolve todo mundo para onde estava, e volta ao estado de espera. */
    private fun encerrar(server: MinecraftServer, motivo: String) {
        aviso(server, motivo, Formatting.YELLOW)

        lutas.toList().forEach { BatalhaCobblemon.encerrarBatalhaDe(it.a) }
        lutas.clear()

        vivos.toList().forEach { uuid ->
            server.playerManager.getPlayer(uuid)?.let {
                BatalhaCobblemon.curar(it)
                retornar(server, it)
            }
        }
        resetar()
    }

    /** Teleporta o jogador de volta e só apaga o registro se deu certo. */
    private fun retornar(server: MinecraftServer, player: ServerPlayerEntity) {
        val pos = retornos.obter(player.uuid) ?: return
        if (Teleporte.levar(server, player, pos)) {
            retornos.remover(player.uuid)
        } else {
            ColiseuMod.LOGGER.error("Coliseu: could not send ${player.name.string} back (dimension ${pos.dimensao})")
        }
    }

    private fun resetar() {
        estado = Estado.IDLE
        jogadores.clear()
        vivos.clear()
        eliminados.clear()
        teveBye.clear()
        filaLutas.clear()
        lutas.clear()
        avisosFeitos.clear()
        rodada = 0
        agendarProximo()
    }

    private fun agendarProximo() {
        proximaAberturaMs = System.currentTimeMillis() + config.intervaloMinutos * 60_000L
    }

    // ---------- mensagens ----------

    private fun nome(uuid: UUID): String = nomes[uuid] ?: "?"

    /** Texto da regra de lendários + nível de batalha ou nível máximo (se houver). */
    private fun textoRegra(limite: Int): String {
        val lendarios = when {
            limite < 0 -> Msg.t("rule.unlimited")
            limite == 0 -> Msg.t("rule.none")
            limite == 1 -> Msg.t("rule.one")
            else -> Msg.t("rule.many", "n" to limite)
        }
        val nivel = when {
            config.nivelBatalha > 0 -> Msg.t("rule.scaled", "n" to config.nivelBatalha)
            config.nivelMaximo < 100 -> Msg.t("rule.max_level", "n" to config.nivelMaximo)
            else -> ""
        }
        return lendarios + nivel
    }

    private fun anunciar(server: MinecraftServer, segundosRestantes: Int) {
        val msg = prefixo()
            .append(
                Text.literal(
                    Msg.t(
                        "queue.countdown",
                        "time" to formatarTempo(segundosRestantes),
                        "count" to jogadores.size,
                        "max" to config.maxJogadores
                    )
                ).formatted(Formatting.YELLOW)
            )
            .append(botaoEntrar())
        broadcast(server, msg)
    }

    private fun aviso(server: MinecraftServer, texto: String, cor: Formatting) {
        broadcast(server, prefixo().append(Text.literal(texto).formatted(cor)))
    }

    private fun botaoEntrar(): MutableText =
        Text.literal(Msg.t("button.join")).styled {
            it.withColor(Formatting.GREEN)
                .withBold(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, Msg.t("cmd.join_click")))
        }

    private fun prefixo(): MutableText =
        Text.literal(Msg.t("prefix")).formatted(Formatting.GOLD, Formatting.BOLD)

    private fun broadcast(server: MinecraftServer, texto: Text) {
        server.playerManager.broadcast(texto, false)
    }

    private fun formatarTempo(segundos: Int): String =
        if (segundos >= 60 && segundos % 60 == 0) {
            Msg.t("time.minutes", "n" to segundos / 60)
        } else {
            Msg.t("time.seconds", "n" to segundos)
        }
}
