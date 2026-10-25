package satiru.coliseumod

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Uma forma de entregar um prêmio: uma lista de comandos executados pelo servidor.
 * {player} (ou {jogador}) é trocado pelo nome de quem ganhou.
 * Exemplo: "give {player} cobblemon:rare_candy 15".
 * Se um prêmio tem várias opções, uma é sorteada (ex.: "Random legendary" com várias espécies).
 */
data class OpcaoPremio(
    var nome: String? = null,
    var comandos: List<String> = emptyList()
)

/**
 * Sorteia um item do jogo pelo ID. Um item só entra no sorteio se:
 *  - o ID contiver TODOS os textos de [filtros];
 *  - o ID NÃO contiver nenhum texto de [excluir];
 *  - o ID bater com [regex] (se houver).
 * Precisa ter pelo menos um filtro ou uma regex.
 */
data class FiltroItem(
    var filtros: List<String> = emptyList(),
    var excluir: List<String> = emptyList(),
    var regex: String? = null,
    var quantidade: Int = 1
)

data class Premio(
    var nome: String = "",
    /** Peso no sorteio. Se a soma dos pesos for 100, o peso é a própria porcentagem. */
    var peso: Int = 1,
    /** true = mensagem dourada em negrito com estrelas (para prêmios raros). */
    var destaque: Boolean = false,
    var opcoes: List<OpcaoPremio> = emptyList(),
    var itemAleatorio: FiltroItem? = null,
    /** Só um lembrete para você; o mod ignora este campo. */
    var nota: String? = null
) {
    /** Um prêmio só entra no sorteio se souber como ser entregue. */
    val configurado: Boolean
        get() = opcoes.any { it.comandos.isNotEmpty() } || itemAleatorio != null
}

/** Salvo em config/coliseu_loot.json. */
data class LootConfig(
    /** Quantos sorteios cada colocação ganha. 0 = não ganha nada. */
    var sorteiosCampeao: Int = 1,
    var sorteiosVice: Int = 1,
    var sorteiosTerceiro: Int = 1,
    var sorteiosParticipacao: Int = 0,
    var premios: List<Premio> = premiosPadrao()
) {

    fun validar() {
        sorteiosCampeao = sorteiosCampeao.coerceIn(0, 20)
        sorteiosVice = sorteiosVice.coerceIn(0, 20)
        sorteiosTerceiro = sorteiosTerceiro.coerceIn(0, 20)
        sorteiosParticipacao = sorteiosParticipacao.coerceIn(0, 20)
        premios.forEach { it.peso = it.peso.coerceAtLeast(0) }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        private fun caminho() = FabricLoader.getInstance().configDir.resolve("coliseu_loot.json")

        /** Lê o arquivo. Retorna null se o JSON estiver quebrado. Cria o arquivo padrão se não existir. */
        fun tentarCarregar(): LootConfig? {
            val arquivo = caminho()
            return try {
                if (Files.exists(arquivo)) {
                    val lida = Files.newBufferedReader(arquivo).use {
                        gson.fromJson(it, LootConfig::class.java)
                    } ?: return null
                    lida.validar()
                    lida
                } else {
                    LootConfig().also { Files.writeString(arquivo, gson.toJson(it)) }
                }
            } catch (e: Exception) {
                ColiseuMod.LOGGER.error("Erro ao ler coliseu_loot.json", e)
                null
            }
        }
    }
}

private val LENDARIOS = listOf(
    "articuno", "zapdos", "moltres", "mewtwo",
    "raikou", "entei", "suicune", "lugia", "hooh",
    "regirock", "regice", "registeel", "latias", "latios", "kyogre", "groudon", "rayquaza",
    "uxie", "mesprit", "azelf", "dialga", "palkia", "heatran", "regigigas", "giratina", "cresselia",
    "cobalion", "terrakion", "virizion", "tornadus", "thundurus", "landorus", "reshiram", "zekrom", "kyurem",
    "xerneas", "yveltal", "zygarde", "solgaleo", "lunala", "necrozma",
    "zacian", "zamazenta", "eternatus", "regieleki", "regidrago", "glastrier", "spectrier", "calyrex",
    "koraidon", "miraidon", "enamorus"
)

/**
 * Loot pool de exemplo, usando só itens do Cobblemon (funciona em qualquer servidor).
 * Troque pelo que quiser. Um prêmio cujo item não exista no seu servidor fica fora do sorteio
 * e o mod avisa os admins (veja o comando de listar prêmios).
 */
fun premiosPadrao(): List<Premio> = listOf(
    Premio(
        nome = "15x Rare Candy",
        peso = 50,
        opcoes = listOf(OpcaoPremio(null, listOf("give {player} cobblemon:rare_candy 15")))
    ),
    Premio(
        nome = "10x Ultra Ball",
        peso = 25,
        opcoes = listOf(OpcaoPremio(null, listOf("give {player} cobblemon:ultra_ball 10")))
    ),
    Premio(
        nome = "5x Exp. Candy XL",
        peso = 15,
        opcoes = listOf(OpcaoPremio(null, listOf("give {player} cobblemon:exp_candy_xl 5")))
    ),
    Premio(
        nome = "Master Ball",
        peso = 9,
        destaque = true,
        opcoes = listOf(OpcaoPremio(null, listOf("give {player} cobblemon:master_ball 1")))
    ),
    Premio(
        nome = "Random legendary",
        peso = 1,
        destaque = true,
        opcoes = LENDARIOS.map { especie ->
            OpcaoPremio(
                nome = especie.replaceFirstChar { it.uppercase() },
                comandos = listOf("givepokemon {player} $especie level=70")
            )
        },
        nota = "Edit the species list and the level as you like."
    )
)
