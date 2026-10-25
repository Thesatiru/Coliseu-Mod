package satiru.coliseumod

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/** Uma posição no mundo (dimensão + coordenadas + direção do olhar). */
data class PosicaoConfig(
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
 * Configuração salva em config/coliseu.json.
 * Lobby e arenas são definidos no jogo (setlobby e arena <n> pos1|pos2).
 */
data class ColiseuConfig(
    /** Idioma das mensagens: "en_us", "pt_br" ou o código de um arquivo em config/coliseu_lang/. */
    var idioma: String = "en_us",

    // fila e agendamento
    var intervaloMinutos: Int = 60,
    var filaSegundos: Int = 60,
    var minJogadores: Int = 2,
    var maxJogadores: Int = 8,
    var avisosEmSegundos: List<Int> = listOf(60, 30, 10),

    // tempos do torneio
    /** Tempo máximo sem nenhum progresso (luta começando/terminando) antes de encerrar tudo. */
    var tempoMaximoSegundos: Int = 600,
    /** Duração máxima de uma luta. Passou disso, o vencedor é sorteado. */
    var tempoBatalhaSegundos: Int = 480,
    var pausaEntreLutasSegundos: Int = 5,
    /** Segundos entre o teleporte e o início da luta. 0 = a luta começa na hora. */
    var contagemLutaSegundos: Int = 0,

    // regras de time
    /** Times com algum Pokémon acima deste nível não entram. 100 = sem limite. */
    var nivelMaximo: Int = 100,
    /**
     * Regras possíveis de lendários. A cada torneio uma é sorteada.
     * -1 = tudo liberado | 0 = nenhum lendário | 1 = no máximo 1 | 3 = no máximo 3 ...
     */
    var opcoesLimitados: List<Int> = listOf(-1, 1, 3),
    /** Labels do Cobblemon que contam como "lendário". */
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
            ColiseuMod.LOGGER.error("Não foi possível salvar coliseu.json", e)
        }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        private fun caminho() = FabricLoader.getInstance().configDir.resolve("coliseu.json")

        /** Lê o arquivo. Retorna null se o JSON estiver quebrado. Cria o arquivo se não existir. */
        fun tentarCarregar(): ColiseuConfig? {
            val arquivo = caminho()
            return try {
                if (Files.exists(arquivo)) {
                    val lida = Files.newBufferedReader(arquivo).use {
                        gson.fromJson(it, ColiseuConfig::class.java)
                    } ?: return null
                    val tinhaFormatoAntigo = lida.posLutador1 != null || lida.posLutador2 != null
                    lida.validar()
                    // grava já no formato novo, para as posições antigas não se perderem
                    if (tinhaFormatoAntigo) Files.writeString(arquivo, gson.toJson(lida))
                    lida
                } else {
                    ColiseuConfig().also { Files.writeString(arquivo, gson.toJson(it)) }
                }
            } catch (e: Exception) {
                ColiseuMod.LOGGER.error("Erro ao ler coliseu.json", e)
                null
            }
        }

        fun carregar(): ColiseuConfig = tentarCarregar() ?: ColiseuConfig()
    }
}
