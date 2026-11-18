import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable
enum class State {
    PREPARED, DISPATCHED, ACKNOWLEDGED, UNCERTAIN
}

@Serializable
data class Entry(
    val id: String,
    val intent: String,
    val state: State,
    val timestamp: String,
    val metadata: Map<String, String>? = null
)

fun main() {
    val entry = Entry("1", "test", State.PREPARED, Instant.now().toString())
    val jsonStr = Json.encodeToString(entry)
    println("Serialized: $jsonStr")
    val deserialized = Json.decodeFromString<Entry>(jsonStr)
    println("Deserialized: $deserialized")
    if (entry == deserialized) {
        println("SUCCESS")
    } else {
        println("FAILURE")
    }
}
