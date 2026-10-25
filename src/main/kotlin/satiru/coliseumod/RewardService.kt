package satiru.coliseumod

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

/** Um prêmio já sorteado e pronto para ser entregue. */
data class PremioSorteado(
    var nome: String = "",
    var destaque: Boolean = false,
    var comandos: List<String> = emptyList(),
    var rotulo: String = ""
)

/**
 * Sorteia, entrega e anuncia as recompensas do torneio.
 * Quem não estiver online na hora recebe ao entrar no servidor (fica salvo em disco).
 */
class RewardService {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val caminhoPendentes =
        FabricLoader.getInstance().configDir.resolve("coliseu_premios_pendentes.json")

    private var loot: LootConfig = LootConfig.tentarCarregar() ?: LootConfig()
    private val pendentes = LinkedHashMap<String, MutableList<PremioSorteado>>()

    init {
        carregarPendentes()
        val faltando = naoConfigurados()
        if (faltando.isNotEmpty()) {
            ColiseuMod.LOGGER.warn("Coliseu: rewards not configured (excluded from the draw): $faltando")
        }
    }

    // ---------- configuração ----------

    /** Relê coliseu_loot.json. Retorna false se estiver com erro (mantém o anterior). */
    fun recarregar(): Boolean {
        val nova = LootConfig.tentarCarregar() ?: return false
        loot = nova
        val faltando = naoConfigurados()
        if (faltando.isNotEmpty()) {
            ColiseuMod.LOGGER.warn("Coliseu: rewards not configured (excluded from the draw): $faltando")
        }
        return true
    }

    fun naoConfigurados(): List<String> =
        loot.premios.filter { it.peso > 0 && !it.configurado }.map { it.nome }

    /** Avisa os admins online se há prêmios fora do sorteio. */
    fun avisarAdmins(server: MinecraftServer) {
        val faltando = naoConfigurados()
        if (faltando.isEmpty()) return
        val msg = Text.literal(
            Msg.t("reward.warn_admin", "n" to faltando.size, "list" to faltando.joinToString())
        ).formatted(Formatting.RED)
        server.playerManager.playerList
            .filter { Permissao.admin(it.commandSource) }
            .forEach { it.sendMessage(msg, false) }
    }

    /** Lista de prêmios com a chance de cada um e se está configurado. */
    fun descreverPremios(): List<String> {
        val total = loot.premios.sumOf { it.peso }.coerceAtLeast(1)
        val linhas = mutableListOf<String>()
        loot.premios.forEachIndexed { i, p ->
            val chance = "%.1f".format(p.peso * 100.0 / total)
            val status = if (p.configurado) Msg.t("reward.status_ok") else Msg.t("reward.status_missing")
            linhas.add(Msg.t("reward.list_line", "i" to (i + 1), "name" to p.nome, "chance" to chance, "status" to status))
        }
        linhas.add(
            Msg.t(
                "reward.rolls",
                "c" to loot.sorteiosCampeao,
                "v" to loot.sorteiosVice,
                "t" to loot.sorteiosTerceiro,
                "p" to loot.sorteiosParticipacao
            )
        )
        if (naoConfigurados().isNotEmpty()) {
            linhas.add(Msg.t("reward.renormalize"))
        }
        return linhas
    }

    /**
     * Itens que podem sair no prêmio [numero] (1 = primeiro da lista), para o admin conferir.
     * Retorna null se o prêmio não existe ou não usa itemAleatorio.
     */
    fun itensDoPremio(numero: Int): List<String>? {
        val f = loot.premios.getOrNull(numero - 1)?.itemAleatorio ?: return null
        return itensQueCombinam(f)?.map { it.toString() }
    }

    // ---------- sorteio ----------

    /** Sorteia entre os prêmios configurados, respeitando os pesos. */
    fun sortear(): PremioSorteado? {
        val candidatos = loot.premios.filter { it.peso > 0 && it.configurado }.toMutableList()
        while (candidatos.isNotEmpty()) {
            val total = candidatos.sumOf { it.peso }
            var r = Random.nextInt(total)
            var escolhido = candidatos.last()
            for (p in candidatos) {
                r -= p.peso
                if (r < 0) {
                    escolhido = p
                    break
                }
            }
            resolver(escolhido)?.let { return it }
            candidatos.remove(escolhido) // não deu para resolver (ex.: filtro sem itens): tenta outro
        }
        return null
    }

    /** Força o prêmio de número [numero] (1 = primeiro da lista). Usado nos testes. */
    fun premioNumero(numero: Int): PremioSorteado? {
        val p = loot.premios.getOrNull(numero - 1) ?: return null
        return resolver(p)
    }

    /** IDs de item que passam pelo filtro. Retorna null se o filtro for inválido. */
    private fun itensQueCombinam(f: FiltroItem): List<Identifier>? {
        val regex = f.regex?.takeIf { it.isNotBlank() }?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                ColiseuMod.LOGGER.warn("Coliseu: invalid regex in itemAleatorio: $it")
                return null
            }
        }
        if (f.filtros.isEmpty() && regex == null) {
            ColiseuMod.LOGGER.warn("Coliseu: itemAleatorio without filters or regex (ignored).")
            return null
        }
        return Registries.ITEM.ids
            .filter { id ->
                val s = id.toString()
                s != "minecraft:air" &&
                    f.filtros.all { s.contains(it, ignoreCase = true) } &&
                    f.excluir.none { s.contains(it, ignoreCase = true) } &&
                    (regex == null || regex.containsMatchIn(s))
            }
            .sortedBy { it.toString() }
    }

    private fun resolver(p: Premio): PremioSorteado? {
        val opcoes = p.opcoes.filter { it.comandos.isNotEmpty() }
        if (opcoes.isNotEmpty()) {
            val o = opcoes.random()
            val nome = if (o.nome.isNullOrBlank()) p.nome else "${p.nome} (${o.nome})"
            return PremioSorteado(nome, p.destaque, o.comandos)
        }

        val f = p.itemAleatorio ?: return null
        val candidatos = itensQueCombinam(f) ?: return null
        if (candidatos.isEmpty()) {
            ColiseuMod.LOGGER.warn("Coliseu: no item found for reward '${p.nome}'.")
            return null
        }
        val id = candidatos.random()
        val nomeItem = Registries.ITEM.get(id).name.string
        val qtd = f.quantidade.coerceAtLeast(1)
        return PremioSorteado("${p.nome} ($nomeItem)", p.destaque, listOf("give {player} $id $qtd"))
    }

    // ---------- entrega ----------

    /** Entrega os prêmios do pódio. [colocacao]: campeão primeiro, depois 2º, 3º e os demais. */
    fun entregarPodio(server: MinecraftServer, colocacao: List<UUID>, nome: (UUID) -> String) {
        colocacao.forEachIndexed { i, uuid ->
            val (rotulo, sorteios) = when (i) {
                0 -> Msg.t("podium.first") to loot.sorteiosCampeao
                1 -> Msg.t("podium.second") to loot.sorteiosVice
                2 -> Msg.t("podium.third") to loot.sorteiosTerceiro
                else -> Msg.t("podium.participation") to loot.sorteiosParticipacao
            }
            repeat(sorteios) {
                val premio = sortear()
                if (premio == null) {
                    ColiseuMod.LOGGER.error("Coliseu: no reward configured to roll!")
                    return@repeat
                }
                entregar(server, uuid, nome(uuid), rotulo, premio)
            }
        }
    }

    /** Entrega (ou guarda, se o jogador estiver offline) e anuncia no chat. */
    fun entregar(server: MinecraftServer, uuid: UUID, nomeJogador: String, rotulo: String, premio: PremioSorteado) {
        val jogador = server.playerManager.getPlayer(uuid)
        if (jogador == null) {
            pendentes.getOrPut(uuid.toString()) { mutableListOf() }.add(premio.copy(rotulo = rotulo))
            salvarPendentes()
            broadcast(
                server,
                prefixo().append(
                    Text.literal(
                        Msg.t("reward.offline", "label" to rotulo, "player" to nomeJogador, "prize" to premio.nome)
                    ).formatted(Formatting.AQUA)
                )
            )
            return
        }

        if (executar(server, premio.comandos, jogador.name.string)) {
            anunciar(server, rotulo, jogador.name.string, premio)
        } else {
            falhaNaEntrega(server, jogador, premio)
        }
    }

    /** Chamado quando o jogador entra no servidor: entrega o que ficou pendente. */
    fun entregarPendentes(server: MinecraftServer, player: ServerPlayerEntity) {
        val lista = pendentes.remove(player.uuid.toString()) ?: return
        salvarPendentes()
        server.execute {
            lista.forEach { premio ->
                val rotulo = premio.rotulo.ifBlank { Msg.t("reward.pending_label") }
                if (executar(server, premio.comandos, player.name.string)) {
                    anunciar(server, rotulo, player.name.string, premio)
                } else {
                    falhaNaEntrega(server, player, premio)
                }
            }
        }
    }

    /** Roda os comandos como console. Retorna false se algum deu erro. */
    private fun executar(server: MinecraftServer, comandos: List<String>, jogador: String): Boolean {
        var ok = true
        comandos.forEach { bruto ->
            val comando = bruto.trim().removePrefix("/")
                .replace("{player}", jogador)
                .replace("{jogador}", jogador)
            try {
                server.commandManager.dispatcher.execute(comando, server.commandSource)
            } catch (e: Exception) {
                ok = false
                ColiseuMod.LOGGER.error("Coliseu: reward command failed: $comando", e)
            }
        }
        return ok
    }

    private fun falhaNaEntrega(server: MinecraftServer, jogador: ServerPlayerEntity, premio: PremioSorteado) {
        jogador.sendMessage(
            Text.literal(Msg.t("reward.fail_player", "prize" to premio.nome)).formatted(Formatting.RED),
            false
        )
        val aviso = Text.literal(
            Msg.t("reward.fail_admin", "prize" to premio.nome, "player" to jogador.name.string)
        ).formatted(Formatting.RED)
        server.playerManager.playerList
            .filter { Permissao.admin(it.commandSource) }
            .forEach { it.sendMessage(aviso, false) }
    }

    // ---------- chat ----------

    private fun anunciar(server: MinecraftServer, rotulo: String, jogador: String, premio: PremioSorteado) {
        val chave = if (premio.destaque) "reward.announce_star" else "reward.announce"
        val texto = Msg.t(chave, "label" to rotulo, "player" to jogador, "prize" to premio.nome)
        val corpo = if (premio.destaque) {
            Text.literal(texto).formatted(Formatting.GOLD, Formatting.BOLD)
        } else {
            Text.literal(texto).formatted(Formatting.AQUA)
        }
        broadcast(server, prefixo().append(corpo))
    }

    private fun prefixo(): MutableText =
        Text.literal(Msg.t("prefix")).formatted(Formatting.GOLD, Formatting.BOLD)

    private fun broadcast(server: MinecraftServer, texto: Text) {
        server.playerManager.broadcast(texto, false)
    }

    // ---------- prêmios pendentes (disco) ----------

    private fun carregarPendentes() {
        try {
            if (Files.exists(caminhoPendentes)) {
                val tipo = object : TypeToken<LinkedHashMap<String, MutableList<PremioSorteado>>>() {}.type
                val lido = Files.newBufferedReader(caminhoPendentes).use {
                    gson.fromJson<LinkedHashMap<String, MutableList<PremioSorteado>>>(it, tipo)
                }
                if (lido != null) pendentes.putAll(lido)
            }
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao ler coliseu_premios_pendentes.json", e)
        }
    }

    private fun salvarPendentes() {
        try {
            Files.writeString(caminhoPendentes, gson.toJson(pendentes))
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao salvar coliseu_premios_pendentes.json", e)
        }
    }
}
