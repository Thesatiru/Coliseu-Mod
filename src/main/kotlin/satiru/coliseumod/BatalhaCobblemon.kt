package satiru.coliseumod

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * TODA a comunicação com a API do Cobblemon fica neste arquivo.
 * Se uma atualização do Cobblemon mudar alguma função, só este arquivo precisa de ajuste.
 */
object BatalhaCobblemon {

    private fun pokemons(player: ServerPlayerEntity) =
        Cobblemon.storage.getParty(player).toList()

    /**
     * Retorna uma mensagem de erro se o time não atende às regras, ou null se estiver ok.
     * [limiteLendarios]: -1 = sem limite, 0 = nenhum, 1 = no máximo 1, etc.
     */
    fun validarTime(player: ServerPlayerEntity, config: ColiseuConfig, limiteLendarios: Int): String? {
        val time = pokemons(player)
        if (time.isEmpty()) return Msg.t("team.empty")

        // com nível de batalha fixo, o nível real não importa: o limite de nível não vale
        val acima = if (config.nivelBatalha > 0) null else time.firstOrNull { it.level > config.nivelMaximo }
        if (acima != null) {
            return Msg.t("team.level", "species" to acima.species.name, "max" to config.nivelMaximo)
        }

        if (limiteLendarios >= 0) {
            val proibidas = config.labelsLimitados.map { it.lowercase() }.toSet()
            val limitados = time.count { p -> p.species.labels.any { it.lowercase() in proibidas } }
            if (limitados > limiteLendarios) {
                val permitido = if (limiteLendarios == 0) {
                    Msg.t("team.allowed_none")
                } else {
                    Msg.t("team.allowed_up_to", "n" to limiteLendarios)
                }
                return Msg.t("team.legendary", "count" to limitados, "allowed" to permitido)
            }
        }
        return null
    }

    fun curar(player: ServerPlayerEntity) {
        try {
            Cobblemon.storage.getParty(player).heal()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao curar time de ${player.name.string}", e)
        }
    }

    fun emBatalha(player: ServerPlayerEntity): Boolean =
        BattleRegistry.getBattleByParticipatingPlayer(player) != null

    /**
     * Inicia a batalha 1v1. Retorna true se os dois jogadores ficaram em batalha.
     * [nivel] > 0: todos lutam nesse nível, pelo mesmo ajuste de nível do desafio do Cobblemon
     * (BattleFormat.adjustLevel). O Cobblemon monta cópias dos times só para a batalha,
     * então os Pokémon reais não mudam. [nivel] = 0: lutam com os times e níveis reais.
     */
    fun iniciarBatalha(a: ServerPlayerEntity, b: ServerPlayerEntity, nivel: Int): Boolean {
        return try {
            if (nivel > 0) {
                val formato = BattleFormat.GEN_9_SINGLES.copy(adjustLevel = nivel)
                BattleBuilder.pvp1v1(a, b, null, null, formato)
            } else {
                BattleBuilder.pvp1v1(a, b)
            }
            emBatalha(a) && emBatalha(b)
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao iniciar batalha ${a.name.string} x ${b.name.string}", e)
            false
        }
    }

    /** Encerra a batalha em que o jogador estiver (se houver). */
    fun encerrarBatalhaDe(uuid: UUID) {
        try {
            BattleRegistry.getBattleByParticipatingPlayerId(uuid)?.end()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao encerrar batalha de $uuid", e)
        }
    }

    /** Liga os eventos de fim de batalha ao gerenciador do torneio. */
    fun registrarEventos(manager: TournamentManager) {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { evento ->
            val vencedores = evento.winners.flatMap { it.getPlayerUUIDs().toList() }
            val perdedores = evento.losers.flatMap { it.getPlayerUUIDs().toList() }
            manager.aoFimDeBatalha(vencedores, perdedores)
        }

        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { evento ->
            manager.aoFugir(evento.player.uuid)
        }
    }
}
