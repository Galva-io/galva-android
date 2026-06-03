package io.galva.network

/**
 * Case-insensitive header map. Lookups normalize to lowercase.
 * Immutable from the caller's perspective — use [builder] to compose.
 */
class HttpHeaders private constructor(
    private val map: Map<String, String>,
) {
    operator fun get(name: String): String? = map[name]

    fun values() = map
    fun names(): Set<String> = map.keys
    fun isEmpty(): Boolean = map.isEmpty()
    fun forEach(block: (name: String, value: String) -> Unit) =
        map.forEach { (k, v) -> block(k, v) }

    fun newBuilder(): Builder = Builder().also { b ->
        map.forEach { (k, v) -> b.add(k, v) }
    }

    override fun toString() = map.toString()

    class Builder {
        private val map = mutableMapOf<String, String>()

        fun add(name: String, value: String) = apply {
            map.getOrPut(name) {
                value
            }
        }

        fun set(name: String, value: String) = apply {
            map[name] = value
        }

        fun remove(name: String) = apply { map.remove(name) }

        fun build(): HttpHeaders = HttpHeaders(map)
    }

    companion object {
        @JvmField
        val EMPTY = HttpHeaders(emptyMap())

        @JvmStatic
        fun of(vararg pairs: Pair<String, String>): HttpHeaders =
            Builder().apply { pairs.forEach { (k, v) -> add(k, v) } }.build()
    }
}