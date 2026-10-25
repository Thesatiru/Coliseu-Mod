package satiru.coliseumod

import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier

object Teleporte {

    /** Captura a posição atual do jogador (dimensão, coordenadas e direção). */
    fun posicaoAtual(player: ServerPlayerEntity): PosicaoConfig = PosicaoConfig(
        dimensao = player.world.registryKey.value.toString(),
        x = player.x,
        y = player.y,
        z = player.z,
        yaw = player.yaw,
        pitch = player.pitch
    )

    /** Teleporta o jogador. Retorna false se a dimensão não existir. */
    fun levar(server: MinecraftServer, player: ServerPlayerEntity, pos: PosicaoConfig): Boolean {
        val id = Identifier.tryParse(pos.dimensao) ?: return false
        val chave = RegistryKey.of(RegistryKeys.WORLD, id)
        val mundo = server.getWorld(chave) ?: return false
        player.teleport(mundo, pos.x, pos.y, pos.z, pos.yaw, pos.pitch)
        return true
    }
}
