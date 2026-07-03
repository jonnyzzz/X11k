package org.jonnyzzz.xserver

fun main(args: Array<String>) {
    val options = ServerOptions.parse(args)
    XServer(options).use { server ->
        println("X server listening on ${options.host}:${options.port}")
        server.serveForever()
    }
}

data class ServerOptions(
    val host: String = "127.0.0.1",
    val port: Int = 6000,
    val width: Int = 1024,
    val height: Int = 768,
    val dpi: Int = 96,
    val rootBackgroundPixel: Int = 0x00ff_ffff,
) {
    companion object {
        fun parse(args: Array<String>): ServerOptions {
            var host = "127.0.0.1"
            var port = 6000
            var width = 1024
            var height = 768
            var dpi = 96
            var rootBackgroundPixel = 0x00ff_ffff

            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--host" -> host = args.valueAfter(index++, arg)
                    "--port" -> port = args.valueAfter(index++, arg).toInt()
                    "--width" -> width = args.valueAfter(index++, arg).toInt()
                    "--height" -> height = args.valueAfter(index++, arg).toInt()
                    "--dpi" -> dpi = args.valueAfter(index++, arg).toInt()
                    "--root-background" -> rootBackgroundPixel = parseRgbPixel(args.valueAfter(index++, arg), arg)
                    else -> error("Unknown argument: $arg")
                }
                index++
            }

            require(width > 0) { "width must be positive" }
            require(height > 0) { "height must be positive" }
            require(dpi > 0) { "dpi must be positive" }

            return ServerOptions(host, port, width, height, dpi, rootBackgroundPixel)
        }

        private fun parseRgbPixel(value: String, option: String): Int {
            val normalized = value.removePrefix("#").removePrefix("0x").removePrefix("0X")
            val parsed = normalized.toLongOrNull(16)
                ?: error("Invalid RGB pixel for $option: $value")
            require(parsed in 0..0x00ff_ffff) { "$option must be a 24-bit RGB pixel: $value" }
            return parsed.toInt()
        }

        private fun Array<String>.valueAfter(index: Int, option: String): String =
            getOrNull(index + 1) ?: error("Missing value for $option")
    }
}
