package satiru.coliseumod

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/** Nomes dos comandos em um idioma. Os dois conjuntos (EN e PT) são registrados e funcionam igual. */
private class Nomes(
    val raiz: String,
    val entrar: String,
    val sair: String,
    val status: String,
    val ajuda: String,
    val admin: String,
    val abrir: String,
    val cancelar: String,
    val encerrar: String,
    val setlobby: String,
    val arena: String,
    val arenas: String,
    val recarregar: String,
    val premios: String,
    val premiolista: String,
    val premio: String,
    val iditem: String,
    val listaritens: String
)

private val NOMES_EN = Nomes(
    raiz = "colosseum", entrar = "join", sair = "leave", status = "status", ajuda = "help",
    admin = "admin", abrir = "open", cancelar = "cancel", encerrar = "end", setlobby = "setlobby",
    arena = "arena", arenas = "arenas", recarregar = "reload", premios = "rewards",
    premiolista = "rewardlist", premio = "reward", iditem = "itemid", listaritens = "finditems"
)

private val NOMES_PT = Nomes(
    raiz = "coliseu", entrar = "entrar", sair = "sair", status = "status", ajuda = "ajuda",
    admin = "admin", abrir = "abrir", cancelar = "cancelar", encerrar = "encerrar", setlobby = "setlobby",
    arena = "arena", arenas = "arenas", recarregar = "recarregar", premios = "premios",
    premiolista = "premiolista", premio = "premio", iditem = "iditem", listaritens = "listaritens"
)

object ColiseuCommand {

    fun registrar(dispatcher: CommandDispatcher<ServerCommandSource>, manager: TournamentManager) {
        registrarArvore(dispatcher, manager, NOMES_EN)
        registrarArvore(dispatcher, manager, NOMES_PT)
    }

    private fun registrarArvore(
        dispatcher: CommandDispatcher<ServerCommandSource>,
        manager: TournamentManager,
        n: Nomes
    ) {
        dispatcher.register(
            CommandManager.literal(n.raiz)
                .then(
                    CommandManager.literal(n.entrar)
                        .requires { Permissao.jogar(it) }
                        .executes { ctx ->
                            val player = ctx.source.playerOrThrow
                            val erro = manager.entrar(player)
                            if (erro != null) {
                                player.sendMessage(Text.literal(erro).formatted(Formatting.RED), false)
                            } else {
                                player.sendMessage(Text.literal(Msg.t("cmd.joined")).formatted(Formatting.GREEN), false)
                            }
                            1
                        }
                )
                .then(
                    CommandManager.literal(n.sair)
                        .requires { Permissao.jogar(it) }
                        .executes { ctx ->
                            val player = ctx.source.playerOrThrow
                            val saiu = manager.sair(ctx.source.server, player)
                            if (!saiu) {
                                player.sendMessage(Text.literal(Msg.t("cmd.not_in")).formatted(Formatting.RED), false)
                            }
                            1
                        }
                )
                .then(
                    CommandManager.literal(n.status)
                        .requires { Permissao.jogar(it) }
                        .executes { ctx ->
                            ctx.source.sendMessage(Text.literal(manager.statusTexto()).formatted(Formatting.AQUA))
                            1
                        }
                )
                .then(
                    CommandManager.literal(n.ajuda).executes { ctx ->
                        enviarAjuda(ctx.source)
                        1
                    }
                )
                .then(
                    CommandManager.literal(n.admin)
                        .requires { Permissao.admin(it) }
                        .then(
                            CommandManager.literal(n.abrir).executes { ctx ->
                                val ok = manager.abrirAgora(ctx.source.server)
                                if (!ok) ctx.source.sendError(Text.literal(Msg.t("cmd.open_exists")))
                                if (ok) 1 else 0
                            }
                        )
                        .then(
                            CommandManager.literal(n.cancelar).executes { ctx ->
                                val ok = manager.cancelar(ctx.source.server)
                                if (!ok) ctx.source.sendError(Text.literal(Msg.t("cmd.no_queue_open")))
                                if (ok) 1 else 0
                            }
                        )
                        .then(
                            CommandManager.literal(n.encerrar).executes { ctx ->
                                val ok = manager.encerrarAdmin(ctx.source.server)
                                if (!ok) ctx.source.sendError(Text.literal(Msg.t("cmd.no_tournament")))
                                if (ok) 1 else 0
                            }
                        )
                        .then(
                            CommandManager.literal(n.setlobby).executes { ctx ->
                                val player = ctx.source.playerOrThrow
                                val pos = manager.definirLobby(player)
                                player.sendMessage(
                                    Text.literal(
                                        Msg.t(
                                            "cmd.lobby_set",
                                            "x" to "%.1f".format(pos.x),
                                            "y" to "%.1f".format(pos.y),
                                            "z" to "%.1f".format(pos.z),
                                            "dim" to pos.dimensao
                                        )
                                    ).formatted(Formatting.GREEN),
                                    false
                                )
                                1
                            }
                        )
                        .then(
                            // arena <numero> pos1|pos2
                            CommandManager.literal(n.arena).then(
                                CommandManager.argument("numero", IntegerArgumentType.integer(1, 16))
                                    .then(posicaoArena("pos1", 1, manager))
                                    .then(posicaoArena("pos2", 2, manager))
                            )
                        )
                        .then(
                            CommandManager.literal(n.arenas).executes { ctx ->
                                manager.descreverArenas().forEach {
                                    ctx.source.sendMessage(Text.literal(it).formatted(Formatting.AQUA))
                                }
                                1
                            }
                        )
                        .then(
                            CommandManager.literal(n.recarregar).executes { ctx ->
                                val ok = manager.recarregar()
                                if (ok) {
                                    ctx.source.sendMessage(Text.literal(Msg.t("cmd.reloaded")).formatted(Formatting.GREEN))
                                } else {
                                    ctx.source.sendError(Text.literal(Msg.t("cmd.reload_failed")))
                                }
                                if (ok) 1 else 0
                            }
                        )
                        // ----- ferramentas de recompensa -----
                        .then(
                            CommandManager.literal(n.premios).executes { ctx ->
                                manager.recompensas.descreverPremios().forEach {
                                    ctx.source.sendMessage(Text.literal(it).formatted(Formatting.AQUA))
                                }
                                1
                            }
                        )
                        .then(
                            // premiolista <numero>: mostra quais itens podem sair naquele prêmio
                            CommandManager.literal(n.premiolista).then(
                                CommandManager.argument("numero", IntegerArgumentType.integer(1, 50)).executes { ctx ->
                                    val numero = IntegerArgumentType.getInteger(ctx, "numero")
                                    val itens = manager.recompensas.itensDoPremio(numero)
                                    if (itens == null) {
                                        ctx.source.sendError(Text.literal(Msg.t("cmd.rewardlist_invalid", "n" to numero)))
                                        0
                                    } else {
                                        ctx.source.sendMessage(
                                            Text.literal(Msg.t("cmd.rewardlist_header", "n" to numero, "count" to itens.size))
                                                .formatted(Formatting.AQUA)
                                        )
                                        itens.take(40).forEach {
                                            ctx.source.sendMessage(Text.literal(" - $it").formatted(Formatting.GRAY))
                                        }
                                        if (itens.size > 40) {
                                            ctx.source.sendMessage(
                                                Text.literal(Msg.t("cmd.rewardlist_more", "n" to (itens.size - 40)))
                                                    .formatted(Formatting.GRAY)
                                            )
                                        }
                                        1
                                    }
                                }
                            )
                        )
                        .then(
                            // premio <jogador> [numero]: sorteia (ou força o prêmio nº) e entrega
                            CommandManager.literal(n.premio).then(
                                CommandManager.argument("jogador", EntityArgumentType.player())
                                    .executes { ctx -> darPremio(ctx, null, manager) }
                                    .then(
                                        CommandManager.argument("numero", IntegerArgumentType.integer(1, 50))
                                            .executes { ctx ->
                                                darPremio(ctx, IntegerArgumentType.getInteger(ctx, "numero"), manager)
                                            }
                                    )
                            )
                        )
                        .then(
                            // iditem: mostra o ID do item que você está segurando
                            CommandManager.literal(n.iditem).executes { ctx ->
                                val player = ctx.source.playerOrThrow
                                val pilha = player.mainHandStack
                                if (pilha.isEmpty) {
                                    ctx.source.sendError(Text.literal(Msg.t("cmd.hold_item")))
                                    0
                                } else {
                                    val id = Registries.ITEM.getId(pilha.item)
                                    player.sendMessage(
                                        Text.literal(Msg.t("cmd.itemid", "id" to id)).formatted(Formatting.AQUA),
                                        false
                                    )
                                    player.sendMessage(
                                        Text.literal(Msg.t("cmd.itemid_hint")).formatted(Formatting.GRAY),
                                        false
                                    )
                                    1
                                }
                            }
                        )
                        .then(
                            // listaritens <texto>: procura itens pelo ID
                            CommandManager.literal(n.listaritens).then(
                                CommandManager.argument("filtro", StringArgumentType.word()).executes { ctx ->
                                    val filtro = StringArgumentType.getString(ctx, "filtro")
                                    val achados = Registries.ITEM.ids
                                        .map { it.toString() }
                                        .filter { it.contains(filtro, ignoreCase = true) }
                                        .sorted()
                                    ctx.source.sendMessage(
                                        Text.literal(Msg.t("cmd.finditems_header", "n" to achados.size, "filter" to filtro))
                                            .formatted(Formatting.AQUA)
                                    )
                                    achados.take(40).forEach {
                                        ctx.source.sendMessage(Text.literal(" - $it").formatted(Formatting.GRAY))
                                    }
                                    if (achados.size > 40) {
                                        ctx.source.sendMessage(
                                            Text.literal(Msg.t("cmd.finditems_more", "n" to (achados.size - 40)))
                                                .formatted(Formatting.GRAY)
                                        )
                                    }
                                    1
                                }
                            )
                        )
                )
        )
    }

    /** Lista os comandos; os de admin só aparecem para quem tem permissão. */
    private fun enviarAjuda(fonte: ServerCommandSource) {
        fonte.sendMessage(Text.literal(Msg.t("help.header")).formatted(Formatting.GOLD))
        listOf("help.join", "help.leave", "help.status").forEach {
            fonte.sendMessage(Text.literal(Msg.t(it)).formatted(Formatting.YELLOW))
        }
        if (Permissao.admin(fonte)) {
            fonte.sendMessage(Text.literal(Msg.t("help.admin_header")).formatted(Formatting.GOLD))
            listOf(
                "help.admin.open", "help.admin.cancel", "help.admin.end", "help.admin.setlobby",
                "help.admin.arena", "help.admin.arenas", "help.admin.reload", "help.admin.rewards",
                "help.admin.reward", "help.admin.rewardlist", "help.admin.itemid", "help.admin.finditems"
            ).forEach {
                fonte.sendMessage(Text.literal(Msg.t(it)).formatted(Formatting.GRAY))
            }
        }
    }

    /** Sorteia (ou força o prêmio [numero]) e entrega ao jogador indicado, como se fosse do torneio. */
    private fun darPremio(ctx: CommandContext<ServerCommandSource>, numero: Int?, manager: TournamentManager): Int {
        val alvo = EntityArgumentType.getPlayer(ctx, "jogador")
        val premio = if (numero == null) manager.recompensas.sortear() else manager.recompensas.premioNumero(numero)
        if (premio == null) {
            ctx.source.sendError(
                Text.literal(
                    if (numero == null) Msg.t("cmd.reward_none") else Msg.t("cmd.reward_missing", "n" to numero)
                )
            )
            return 0
        }
        manager.recompensas.entregar(
            ctx.source.server, alvo.uuid, alvo.name.string, Msg.t("cmd.reward_test_label"), premio
        )
        return 1
    }

    /** Subcomando que grava a posição atual do admin como pos1 ou pos2 da arena indicada. */
    private fun posicaoArena(nome: String, lado: Int, manager: TournamentManager) =
        CommandManager.literal(nome).executes { ctx ->
            val player = ctx.source.playerOrThrow
            val numero = IntegerArgumentType.getInteger(ctx, "numero")
            val pos = manager.definirArena(player, numero, lado)
            player.sendMessage(
                Text.literal(
                    Msg.t(
                        "cmd.arena_set",
                        "n" to numero,
                        "side" to nome,
                        "x" to "%.1f".format(pos.x),
                        "y" to "%.1f".format(pos.y),
                        "z" to "%.1f".format(pos.z),
                        "dim" to pos.dimensao
                    )
                ).formatted(Formatting.GREEN),
                false
            )
            1
        }
}
