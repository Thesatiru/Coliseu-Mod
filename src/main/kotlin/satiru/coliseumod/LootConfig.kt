package satiru.coliseumod

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.nio.file.Files

/**
 * Uma forma de entregar um prêmio: uma lista de comandos executados pelo servidor.
 * {player} (ou {jogador}) é trocado pelo nome de quem ganhou.
 * Exemplo: "give {player} cobblemon:rare_candy 15".
 * Se um prêmio tem várias opções, uma é sorteada (ex.: "Random legendary" com várias espécies).
 */
data class OpcaoPremio(
    @field:SerializedName("name", alternate = ["nome"])
    var nome: String? = null,
    @field:SerializedName("commands", alternate = ["comandos"])
    var comandos: List<String> = emptyList()
)

/**
 * Sorteia um item do jogo pelo ID. Um item só entra no sorteio se:
 *  - o ID contiver TODOS os textos de [filtros] ("include" no JSON);
 *  - o ID NÃO contiver nenhum texto de [excluir] ("exclude" no JSON);
 *  - o ID bater com [regex] (se houver).
 * Precisa ter pelo menos um filtro ou uma regex.
 */
data class FiltroItem(
    @field:SerializedName("include", alternate = ["filtros"])
    var filtros: List<String> = emptyList(),
    @field:SerializedName("exclude", alternate = ["excluir"])
    var excluir: List<String> = emptyList(),
    var regex: String? = null,
    @field:SerializedName("amount", alternate = ["quantidade"])
    var quantidade: Int = 1
)

data class Premio(
    @field:SerializedName("name", alternate = ["nome"])
    var nome: String = "",
    /** Peso no sorteio. Se a soma dos pesos for 100, o peso é a própria porcentagem. */
    @field:SerializedName("weight", alternate = ["peso"])
    var peso: Int = 1,
    /** true = mensagem dourada em negrito com estrelas (para prêmios raros). */
    @field:SerializedName("highlight", alternate = ["destaque"])
    var destaque: Boolean = false,
    @field:SerializedName("options", alternate = ["opcoes"])
    var opcoes: List<OpcaoPremio> = emptyList(),
    @field:SerializedName("randomItem", alternate = ["itemAleatorio"])
    var itemAleatorio: FiltroItem? = null,
    /** Só um lembrete para você; o mod ignora este campo. */
    @field:SerializedName("note", alternate = ["nota"])
    var nota: String? = null
) {
    /** Um prêmio só entra no sorteio se souber como ser entregue. */
    val configurado: Boolean
        get() = opcoes.any { it.comandos.isNotEmpty() } || itemAleatorio != null
}

/** Salvo em config/colosseum_loot.json (antes: coliseu_loot.json, com chaves em português). */
data class LootConfig(
    /** Quantos sorteios cada colocação ganha. 0 = não ganha nada. */
    @field:SerializedName("championRolls", alternate = ["sorteiosCampeao"])
    var sorteiosCampeao: Int = 1,
    @field:SerializedName("runnerUpRolls", alternate = ["sorteiosVice"])
    var sorteiosVice: Int = 1,
    @field:SerializedName("thirdPlaceRolls", alternate = ["sorteiosTerceiro"])
    var sorteiosTerceiro: Int = 1,
    @field:SerializedName("participationRolls", alternate = ["sorteiosParticipacao"])
    var sorteiosParticipacao: Int = 0,
    @field:SerializedName("rewards", alternate = ["premios"])
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

        private fun caminho() = Arquivos.loot

        /** Chaves usadas até a 0.1.0. Se o arquivo tiver alguma, ele é regravado com as chaves novas. */
        private val CHAVES_ANTIGAS = listOf(
            "sorteiosCampeao", "sorteiosVice", "sorteiosTerceiro", "sorteiosParticipacao", "premios",
            "nome", "peso", "destaque", "opcoes", "itemAleatorio", "nota", "comandos",
            "filtros", "excluir", "quantidade"
        )

        /** Lê o arquivo. Retorna null se o JSON estiver quebrado. Cria o arquivo padrão se não existir. */
        fun tentarCarregar(): LootConfig? {
            val arquivo = caminho()
            return try {
                if (Files.exists(arquivo)) {
                    val texto = Files.readString(arquivo)
                    val lida = gson.fromJson(texto, LootConfig::class.java) ?: return null
                    lida.validar()
                    if (Arquivos.temChavesAntigas(texto, CHAVES_ANTIGAS)) {
                        Files.writeString(arquivo, gson.toJson(lida))
                        ColiseuMod.LOGGER.info("Colosseum: colosseum_loot.json converted to the new key names")
                    }
                    lida
                } else {
                    LootConfig().also { Files.writeString(arquivo, gson.toJson(it)) }
                }
            } catch (e: Exception) {
                ColiseuMod.LOGGER.error("Error reading colosseum_loot.json", e)
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
