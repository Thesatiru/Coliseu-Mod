package satiru.coliseumod

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Caminhos dos arquivos do mod dentro de config/.
 *
 * Até a 0.1.0 os arquivos tinham nomes em português (coliseu.json, coliseu_loot.json...).
 * Na primeira vez que o servidor sobe com a versão nova, o arquivo antigo é renomeado
 * para o nome novo. As chaves antigas dentro dos JSONs continuam sendo lidas
 * (veja os "alternate" nos @SerializedName) e o arquivo é regravado com as chaves novas.
 */
object Arquivos {

    private val dir: Path get() = FabricLoader.getInstance().configDir

    val config: Path get() = migrar("coliseu.json", "colosseum.json")
    val loot: Path get() = migrar("coliseu_loot.json", "colosseum_loot.json")
    val premiosPendentes: Path get() = migrar("coliseu_premios_pendentes.json", "colosseum_pending_rewards.json")
    val retornos: Path get() = migrar("coliseu_retornos.json", "colosseum_returns.json")
    val idiomas: Path get() = migrar("coliseu_lang", "colosseum_lang")

    /** Se só existir o arquivo (ou pasta) antigo, renomeia para o novo. Retorna o caminho novo. */
    private fun migrar(antigo: String, novo: String): Path {
        val caminhoNovo = dir.resolve(novo)
        val caminhoAntigo = dir.resolve(antigo)
        if (!Files.exists(caminhoNovo) && Files.exists(caminhoAntigo)) {
            try {
                Files.move(caminhoAntigo, caminhoNovo)
                ColiseuMod.LOGGER.info("Colosseum: renamed config/$antigo to config/$novo")
            } catch (e: Exception) {
                ColiseuMod.LOGGER.error("Colosseum: could not rename config/$antigo to config/$novo", e)
                return caminhoAntigo
            }
        }
        return caminhoNovo
    }

    /** true se o texto do JSON tiver alguma das [chaves] antigas (para regravar no formato novo). */
    fun temChavesAntigas(json: String, chaves: Collection<String>): Boolean =
        chaves.any { Regex("\"" + Regex.escape(it) + "\"\\s*:").containsMatchIn(json) }
}
