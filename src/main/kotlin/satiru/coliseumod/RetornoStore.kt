package satiru.coliseumod

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.util.UUID

/**
 * Guarda de onde cada jogador veio antes de ser levado ao coliseu.
 * É salvo em disco (config/colosseum_returns.json), então se o servidor cair
 * ou o jogador desconectar dentro da arena, ele ainda volta para casa ao entrar de novo.
 */
class RetornoStore {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val caminho = Arquivos.retornos
    private val mapa = LinkedHashMap<String, PosicaoConfig>()

    init {
        try {
            if (Files.exists(caminho)) {
                val tipo = object : TypeToken<LinkedHashMap<String, PosicaoConfig>>() {}.type
                val lido = Files.newBufferedReader(caminho).use {
                    gson.fromJson<LinkedHashMap<String, PosicaoConfig>>(it, tipo)
                }
                if (lido != null) mapa.putAll(lido)
            }
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Error reading colosseum_returns.json", e)
        }
    }

    fun guardar(uuid: UUID, pos: PosicaoConfig) {
        mapa[uuid.toString()] = pos
        salvar()
    }

    fun obter(uuid: UUID): PosicaoConfig? = mapa[uuid.toString()]

    fun contem(uuid: UUID): Boolean = mapa.containsKey(uuid.toString())

    fun remover(uuid: UUID) {
        if (mapa.remove(uuid.toString()) != null) salvar()
    }

    private fun salvar() {
        try {
            Files.writeString(caminho, gson.toJson(mapa))
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Error saving colosseum_returns.json", e)
        }
    }
}
