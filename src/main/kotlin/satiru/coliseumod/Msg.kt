package satiru.coliseumod

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Textos do mod. Os idiomas embutidos ficam em coliseumod_lang/<codigo>.json dentro do jar
 * (veja [EMBUTIDOS]). O servidor pode:
 *  - escolher o idioma em colosseum.json ("language": "pt_br");
 *  - criar config/colosseum_lang/<codigo>.json para traduzir para outro idioma ou trocar frases
 *    específicas (as chaves que faltarem vêm do idioma embutido e, por fim, do inglês).
 *
 * Variantes regionais sem arquivo próprio usam o idioma mais próximo:
 * es_mx -> es_es, pt_pt -> pt_br, zh_hk -> zh_tw, fr_ca -> fr_fr, de_at -> de_de...
 */
object Msg {

    /** Idiomas que vêm dentro do jar. */
    val EMBUTIDOS = listOf(
        "en_us", "pt_br", "es_es", "fr_fr", "de_de", "it_it", "pl_pl",
        "ru_ru", "uk_ua", "tr_tr", "zh_cn", "zh_tw", "ja_jp", "ko_kr"
    )

    /** Variantes que usam outro arquivo embutido quando não existem por conta própria. */
    private val EQUIVALENTES = mapOf(
        "pt_pt" to "pt_br",
        "zh_hk" to "zh_tw"
    )

    private val gson = Gson()
    private val tipo = object : TypeToken<Map<String, String>>() {}.type

    private var atual: Map<String, String> = emptyMap()
    private var ingles: Map<String, String> = emptyMap()

    fun carregar(idioma: String) {
        val codigo = idioma.trim().lowercase().ifBlank { "en_us" }

        ingles = lerRecurso("en_us")
        val embutidoCodigo = resolverEmbutido(codigo)
        val embutido = when (embutidoCodigo) {
            null -> emptyMap()
            "en_us" -> ingles
            else -> lerRecurso(embutidoCodigo)
        }
        if (embutidoCodigo != null && embutidoCodigo != codigo) {
            ColiseuMod.LOGGER.info("Colosseum: language '$codigo' uses the built-in '$embutidoCodigo' file.")
        }
        val externo = lerExterno(codigo)

        if (embutido.isEmpty() && externo.isEmpty()) {
            ColiseuMod.LOGGER.warn("Colosseum: language '$codigo' not found, using en_us.")
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

    /**
     * Qual arquivo embutido usar para [codigo]: o próprio, um equivalente (pt_pt -> pt_br)
     * ou o primeiro do mesmo idioma (es_mx -> es_es). null se não houver nenhum.
     */
    private fun resolverEmbutido(codigo: String): String? {
        if (codigo in EMBUTIDOS) return codigo
        EQUIVALENTES[codigo]?.let { return it }
        val lingua = codigo.substringBefore('_')
        return EMBUTIDOS.firstOrNull { it.substringBefore('_') == lingua }
    }

    private fun lerRecurso(codigo: String): Map<String, String> {
        return try {
            Msg::class.java.getResourceAsStream("/coliseumod_lang/$codigo.json")?.use { fluxo ->
                InputStreamReader(fluxo, StandardCharsets.UTF_8).use { leitor ->
                    gson.fromJson<Map<String, String>>(leitor, tipo)
                }
            } ?: emptyMap()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Error reading the built-in language $codigo", e)
            emptyMap()
        }
    }

    private fun lerExterno(codigo: String): Map<String, String> {
        return try {
            val arquivo = Arquivos.idiomas.resolve("$codigo.json")
            if (!Files.exists(arquivo)) return emptyMap()
            Files.newBufferedReader(arquivo, StandardCharsets.UTF_8).use { leitor ->
                gson.fromJson<Map<String, String>>(leitor, tipo)
            } ?: emptyMap()
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Error reading config/colosseum_lang/$codigo.json", e)
            emptyMap()
        }
    }
}
