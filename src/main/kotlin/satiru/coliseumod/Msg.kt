package satiru.coliseumod

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Textos do mod. Os idiomas embutidos ficam em coliseumod_lang/<codigo>.json dentro do jar
 * (en_us e pt_br). O servidor pode:
 *  - escolher o idioma em coliseu.json ("idioma": "pt_br");
 *  - criar config/coliseu_lang/<codigo>.json para traduzir para outro idioma ou trocar frases
 *    específicas (as chaves que faltarem vêm do idioma embutido e, por fim, do inglês).
 */
object Msg {

    private val gson = Gson()
    private val tipo = object : TypeToken<Map<String, String>>() {}.type

    private var atual: Map<String, String> = emptyMap()
    private var ingles: Map<String, String> = emptyMap()

    fun carregar(idioma: String) {
        val codigo = idioma.trim().lowercase().ifBlank { "en_us" }

        ingles = lerRecurso("en_us")
        val embutido = if (codigo == "en_us") ingles else lerRecurso(codigo)
        val externo = lerExterno(codigo)

        if (embutido.isEmpty() && externo.isEmpty()) {
            ColiseuMod.LOGGER.warn("Coliseu: idioma '$codigo' não encontrado, usando en_us.")
        }
        atual = embutido + externo
    }

    /** Texto da chave [chave], com {nome} trocado pelos valores de [args]. */
    fun t(chave: String, vararg args: Pair<String, Any?>): String {
        var texto = atual[chave] ?: ingles[chave] ?: return chave
        for ((nome, valor) in args) {
            texto = texto.replace("{$nome}", valor.toString())
        }
        return texto
    }

    private fun lerRecurso(codigo: String): Map<String, String> {
        return try {
            Msg::class.java.getResourceAsStream("/coliseumod_lang/$codigo.json")?.use { fluxo ->
                InputStreamReader(fluxo, StandardCharsets.UTF_8).use { leitor ->
                    gson.fromJson<Map<String, String>>(leitor, tipo)
                }
            } ?: emptyMap()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao ler o idioma embutido $codigo", e)
            emptyMap()
        }
    }

    private fun lerExterno(codigo: String): Map<String, String> {
        return try {
            val arquivo = FabricLoader.getInstance().configDir.resolve("coliseu_lang").resolve("$codigo.json")
            if (!Files.exists(arquivo)) return emptyMap()
            Files.newBufferedReader(arquivo, StandardCharsets.UTF_8).use { leitor ->
                gson.fromJson<Map<String, String>>(leitor, tipo)
            } ?: emptyMap()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Erro ao ler config/coliseu_lang/$codigo.json", e)
            emptyMap()
        }
    }
}
