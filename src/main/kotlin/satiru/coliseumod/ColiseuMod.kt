package satiru.coliseumod

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

class ColiseuMod : ModInitializer {

    override fun onInitialize() {
        val config = ColiseuConfig.carregar()
        Msg.carregar(config.idioma)

        val manager = TournamentManager(config)

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ColiseuCommand.registrar(dispatcher, manager)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            manager.iniciar(server)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            manager.tick(server)
        }

        // quem desconecta sai da fila ou perde a luta (a posição original fica guardada em disco)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            manager.aoDesconectar(handler.player.uuid)
        }

        // quem entra e ficou preso na arena é devolvido ao local de origem
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            manager.aoEntrar(server, handler.player)
        }

        // vitória, derrota e fuga nas batalhas do Cobblemon
        BatalhaCobblemon.registrarEventos(manager)

        LOGGER.info("ColiseuMod loaded!")
    }

    companion object {
        val LOGGER = LoggerFactory.getLogger("coliseumod")!!
    }
}
