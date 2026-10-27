package satiru.coliseumod

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.nio.file.Files

/** Uma posição no mundo (dimensão + coordenadas + direção do olhar). */
data class PosicaoConfig(
    @field:SerializedName("dimension", alternate = ["dimensao"])
    var dimensao: String = "minecraft:overworld",
    var x: Double = 0.0,
    var y: Double = 100.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f
)

/** Uma arena de luta: onde ficam o lutador 1 e o lutador 2. */
data class ArenaConfig(
    var pos1: PosicaoConfig? = null,
    var pos2: PosicaoConfig? = null
) {
    val completa: Boolean get() = pos1 != null && pos2 != null
}

/**
 * Configuração salva em config/colosseum.json (antes: coliseu.json, com chaves em português).
 * Lobby e arenas são definidos no jogo (setlobby e arena <n> pos1|pos2).
 */
data class ColiseuConfig(
    /** Idioma das mensagens: um código embutido (en_us, pt_br, es_es, ...) ou um arquivo em config/colosseum_lang/. */
    @field:SerializedName("language", alternate = ["idioma"])
    var idioma: String = "en_us",

    // fila e agendamento
    @field:SerializedName("intervalMinutes", alternate = ["intervaloMinutos"])
    var intervaloMinutos: Int = 60,
    @field:SerializedName("queueSeconds", alternate = ["filaSegundos"])
    var filaSegundos: Int = 60,
    @field:SerializedName("minPlayers", alternate = ["minJogadores"])
    var minJogadores: Int = 2,
    @field:SerializedName("maxPlayers", alternate = ["maxJogadores"])
    var maxJogadores: Int = 8,
    @field:SerializedName("announceAtSeconds", alternate = ["avisosEmSegundos"])
    var avisosEmSegundos: List<Int> = listOf(60, 30, 10),

    // tempos do torneio
    /** Tempo máximo sem nenhum progresso (luta começando/terminando) antes de encerrar tudo. */
    @field:SerializedName("stallTimeoutSeconds", alternate = ["tempoMaximoSegundos"])
    var tempoMaximoSegundos: Int = 600,
    /** Duração máxima de uma luta. Passou disso, o vencedor é sorteado. */
    @field:SerializedName("battleTimeoutSeconds", alternate = ["tempoBatalhaSegundos"])
    var tempoBatalhaSegundos: Int = 480,
    @field:SerializedName("pauseBetweenFightsSeconds", alternate = ["pausaEntreLutasSegundos"])
    var pausaEntreLutasSegundos: Int = 5,
    /** Segundos entre o teleporte e o início da luta. 0 = a luta começa na hora. */
    @field:SerializedName("fightCountdownSeconds", alternate = ["contagemLutaSegundos"])
    var contagemLutaSegundos: Int = 0,

    // regras de time
    /** Times com algum Pokémon acima deste nível não entram. 100 = sem limite. */
    @field:SerializedName("maxLevel", alternate = ["nivelMaximo"])
    var nivelMaximo: Int = 100,
    /**
     * Regras possíveis de lendários. A cada torneio uma é sorteada.
     * -1 = tudo liberado | 0 = nenhum lendário | 1 = no máximo 1 | 3 = no máximo 3 ...
     */
    @field:SerializedName("legendaryLimitOptions", alternate = ["opcoesLimitados"])
    var opcoesLimitados: List<Int> = listOf(-1, 1, 3),
    /** Labels do Cobblemon que contam como "lendário". */
    @field:SerializedName("legendaryLabels", alternate = ["labelsLimitados"])
    var labelsLimitados: List<String> = listOf("legendary", "mythical", "ultra_beast", "paradox"),

    // posições (definidas pelos comandos de admin)
    var lobby: PosicaoConfig? = null,
    var arenas: MutableList<ArenaConfig> = mutableListOf(),

    // campos antigos (Etapa 3): viram a arena 1 automaticamente ao carregar
    var posLutador1: PosicaoConfig? = null,
    var posLutador2: PosicaoConfig? = null
) {

    /** Copia os valores de outra config para esta (mantém a mesma instância viva). */
    fun aplicar(outra: ColiseuConfig) {
        idioma = outra.idioma
        intervaloMinutos = outra.intervaloMinutos
        filaSegundos = outra.filaSegundos
        minJogadores = outra.minJogadores
        maxJogadores = outra.maxJogadores
        avisosEmSegundos = outra.avisosEmSegundos
        tempoMaximoSegundos = outra.tempoMaximoSegundos
        tempoBatalhaSegundos = outra.tempoBatalhaSegundos
        pausaEntreLutasSegundos = outra.pausaEntreLutasSegundos
        contagemLutaSegundos = outra.contagemLutaSegundos
        nivelMaximo = outra.nivelMaximo
        opcoesLimitados = outra.opcoesLimitados
        labelsLimitados = outra.labelsLimitados
        lobby = outra.lobby
        arenas = outra.arenas
        validar()
    }

    /** Corrige valores absurdos e migra as posições antigas para a lista de arenas. */
    fun validar() {
        idioma = idioma.trim().lowercase().ifBlank { "en_us" }
        intervaloMinutos = intervaloMinutos.coerceAtLeast(1)
        filaSegundos = filaSegundos.coerceAtLeast(5)
        minJogadores = minJogadores.coerceAtLeast(2)
        if (maxJogadores < minJogadores) maxJogadores = minJogadores
        tempoMaximoSegundos = tempoMaximoSegundos.coerceAtLeast(30)
        tempoBatalhaSegundos = tempoBatalhaSegundos.coerceAtLeast(30)
        pausaEntreLutasSegundos = pausaEntreLutasSegundos.coerceAtLeast(1)
        contagemLutaSegundos = contagemLutaSegundos.coerceIn(0, 30)
        nivelMaximo = nivelMaximo.coerceIn(1, 100)
        if (opcoesLimitados.isEmpty()) opcoesLimitados = listOf(-1)

        // migração: posLutador1/2 (Etapa 3) -> arena 1
        val antiga1 = posLutador1
        val antiga2 = posLutador2
        if (antiga1 != null || antiga2 != null) {
            if (arenas.isEmpty()) arenas.add(ArenaConfig(antiga1, antiga2))
            posLutador1 = null
            posLutador2 = null
        }
    }

    /** Devolve a arena de número [numero] (começa em 1), criando as que faltarem. */
    fun arena(numero: Int): ArenaConfig {
        while (arenas.size < numero) arenas.add(ArenaConfig())
        return arenas[numero - 1]
    }

    fun salvar() {
        try {
            Files.writeString(caminho(), gson.toJson(this))
        } catch (e: Exception) {
            ColiseuMod.LOGGER.error("Could not save colosseum.json", e)
        }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        private fun caminho() = Arquivos.config

        /** Chaves usadas até a 0.1.0. Se o arquivo tiver alguma, ele é regravado com as chaves novas. */
        private val CHAVES_ANTIGAS = listOf(
            "idioma", "intervaloMinutos", "filaSegundos", "minJogadores", "maxJogadores", "avisosEmSegundos",
            "tempoMaximoSegundos", "tempoBatalhaSegundos", "pausaEntreLutasSegundos", "contagemLutaSegundos",
            "nivelMaximo", "opcoesLimitados", "labelsLimitados", "dimensao", "posLutador1", "posLutador2"
        )

        /** Lê o arquivo. Retorna null se o JSON estiver quebrado. Cria o arquivo se não existir. */
        fun tentarCarregar(): ColiseuConfig? {
            val arquivo = caminho()
            return try {
                if (Files.exists(arquivo)) {
                    val texto = Files.readString(arquivo)
                    val lida = gson.fromJson(texto, ColiseuConfig::class.java) ?: return null
                    val tinhaFormatoAntigo = Arquivos.temChavesAntigas(texto, CHAVES_ANTIGAS)
                    lida.validar()
                    // grava já no formato novo (chaves em inglês, arenas no lugar de posLutador1/2)
                    if (tinhaFormatoAntigo) {
                        Files.writeString(arquivo, gson.toJson(lida))
                        ColiseuMod.LOGGER.info("Colosseum: colosseum.json converted to the new key names")
                    }
                    lida
                } else {
                    ColiseuConfig().also { Files.writeString(arquivo, gson.toJson(it)) }
                }
            } catch (e: Exception) {
                ColiseuMod.LOGGER.error("Error reading colosseum.json", e)
                null
            }
        }

        fun carregar(): ColiseuConfig = tentarCarregar() ?: ColiseuConfig()
    }
}
