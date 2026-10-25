package satiru.coliseumod

import net.minecraft.command.CommandSource
import net.minecraft.server.command.ServerCommandSource

/**
 * Permissões por nó, compatíveis com LuckPerms (via Fabric Permissions API):
 *  - coliseu.admin : comandos de administração (padrão: OP nível 2)
 *  - coliseu.play  : entrar/sair/status do torneio (padrão: todos)
 *
 * A Fabric Permissions API é opcional. Sem ela, vale o nível de OP.
 * É chamada por reflexão para o mod não depender dela na hora de compilar nem de rodar.
 */
object Permissao {

    private val classe: Class<*>? by lazy {
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions")
        } catch (e: Throwable) {
            null
        }
    }

    private val checkNivel by lazy {
        try {
            classe?.getMethod(
                "check",
                CommandSource::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
            null
        }
    }

    private val checkPadrao by lazy {
        try {
            classe?.getMethod(
                "check",
                CommandSource::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
            null
        }
    }

    fun admin(source: ServerCommandSource): Boolean {
        val metodo = checkNivel
        if (metodo != null) {
            try {
                return metodo.invoke(null, source, "coliseu.admin", 2) as Boolean
            } catch (e: Throwable) {
                // cai no nível de OP
            }
        }
        return source.hasPermissionLevel(2)
    }

    fun jogar(source: ServerCommandSource): Boolean {
        val metodo = checkPadrao
        if (metodo != null) {
            try {
                return metodo.invoke(null, source, "coliseu.play", true) as Boolean
            } catch (e: Throwable) {
                // cai no padrão: liberado
            }
        }
        return true
    }
}
