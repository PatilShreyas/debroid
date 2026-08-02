import com.github.ajalt.clikt.core.*

fun main() {
    val err = CliktError("test")
    println(err.message)
}
