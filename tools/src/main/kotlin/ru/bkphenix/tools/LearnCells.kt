package ru.bkphenix.tools

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.radio.CellMeasurement
import ru.bkphenix.core.radio.CellTechnology
import ru.bkphenix.core.radio.LearnedCellDatabase
import java.io.File
import kotlin.system.exitProcess

/**
 * Начальная загрузка своей базы вышек из прошлых журналов приложения (`phenix_*.jsonl`).
 *
 * `./gradlew :tools:learnCells --args="learned.bklc журнал1.jsonl журнал2.jsonl ..."`
 *
 * Правила те же, что в приложении (PhenixRepository.learnCells): эпоха с целостностью «OK», наблюдение GNSS
 * с σ ≤ 30 м и не дальше 30 м от итога. Если файл-результат уже есть, новые соты складываются с ним.
 * Готовый файл импортируется в приложении или кладётся в `files/cells/learned.bklc`.
 */
public fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Использование: learnCells <результат.bklc> <журнал.jsonl>...")
        exitProcess(2)
    }
    val out = File(args[0])
    val db = LearnedCellDatabase()
    if (out.exists()) out.inputStream().use { db.merge(it) }
    for (path in args.drop(1).sorted()) {
        var epochs = 0; var used = 0; var taught = 0
        File(path).forEachLine { line ->
            if (!line.contains("\"type\":\"epoch\"")) return@forEachLine
            val e = runCatching { MiniJson(line).parse() as Map<*, *> }.getOrNull() ?: return@forEachLine
            epochs++
            val sample = learningSample(e) ?: return@forEachLine
            used++
            taught += db.learn(sample.second, sample.first.first, sample.first.second, (e["t"] as Number).toLong())
        }
        println("$path: эпох $epochs, надёжных $used, учтено точек сот $taught")
    }
    out.outputStream().use { db.write(it) }
    println("Записей: ${db.size}, дают ответ: ${db.matureCount} → $out")
}

/** (точка ГНСС, σ) и соты эпохи, если эпоха годится для обучения. */
internal fun learningSample(e: Map<*, *>): Pair<Pair<GeoPoint, Double>, List<CellMeasurement>>? {
    if ((e["integrity"] as? Map<*, *>)?.get("state") != "OK") return null
    val p = e["point"] as? Map<*, *> ?: return null
    val out = GeoPoint((p["lat"] as? Number ?: return null).toDouble(), (p["lon"] as? Number ?: return null).toDouble())
    val gnss = (e["observations"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().firstOrNull { it["source"] == "GNSS" } ?: return null
    val g = GeoPoint((gnss["lat"] as Number).toDouble(), (gnss["lon"] as Number).toDouble())
    val sigma = (gnss["sigmaM"] as? Number)?.toDouble() ?: return null
    if (sigma > LearnedCellDatabase.MAX_ACCURACY_M || g.distanceTo(out) > LearnedCellDatabase.MAX_ACCURACY_M) return null
    val cells = (e["cells"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { c ->
        val tech = runCatching { CellTechnology.valueOf(c["tech"] as String) }.getOrNull() ?: return@mapNotNull null
        fun int(k: String) = (c[k] as? Number)?.toInt()
        CellMeasurement(tech, int("mcc"), int("mnc"), int("area"), (c["cid"] as? Number)?.toLong(), int("pci"), int("dbm"), int("ta"),
            c["serving"] == true, (c["age"] as? Number)?.toLong())
    }
    return (g to sigma) to cells
}

/** Минимальный разбор JSON для журналов приложения: объекты, массивы, строки, числа, true/false/null. */
internal class MiniJson(private val s: String) {
    private var i = 0
    fun parse(): Any? = value().also { ws() }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> num()
        }
    }
    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); i++ // ':'
            m[k] = value(); ws()
            if (s[i++] == '}') return m
        }
    }
    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>(); i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) { l += value(); ws(); if (s[i++] == ']') return l }
    }
    private fun str(): String {
        val b = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(s[i])
                }
            } else b.append(s[i])
            i++
        }
        i++
        return b.toString()
    }
    private fun num(): Number {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(st, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}
