package com.mezon.mobile.home.chat

internal object WaveWelcome {
    const val STICKER_FILENAME = "hello"
    const val STICKER_SIZE = 374_892
    const val STICKER_WIDTH = 150
    const val STICKER_HEIGHT = 150
    const val SENDER_DISPLAY_NAME = "Mezon"
    const val SENDER_AVATAR_URL =
        "https://cdn.komu.vn/0/1840653409082937344/1782991817428439000/1748500199026_0logo_new.png"

    private val stickerUrls = listOf(
        "https://cdn.komu.vn/stickers/hellomezon.gif",
        "https://cdn.komu.vn/stickers/hellomezon.gif",
        "https://cdn.komu.vn/stickers/music_boy.gif",
        "https://cdn.komu.vn/stickers/music_girl.gif",
        "https://cdn.komu.vn/stickers/d1.gif",
        "https://cdn.komu.vn/stickers/d2.gif",
        "https://cdn.komu.vn/stickers/d3.gif",
        "https://cdn.komu.vn/stickers/d4.gif",
        "https://cdn.komu.vn/stickers/d5.gif",
        "https://cdn.komu.vn/stickers/whatsapp.gif",
        "https://cdn.komu.vn/stickers/zalo.gif",
        "https://cdn.komu.vn/stickers/mezon.gif",
        "https://cdn.komu.vn/stickers/telegram.gif",
        "https://cdn.komu.vn/stickers/mezon.gif",
        "https://cdn.komu.vn/stickers/slack.gif",
        "https://cdn.komu.vn/stickers/mezon.gif",
        "https://cdn.komu.vn/stickers/discord.gif",
        "https://cdn.komu.vn/stickers/mezon.gif",
        "https://cdn.komu.vn/landing-page-mezon/2021919345600368640.gif",
    )

    fun stickerUrl(timestampSeconds: Long): String {
        val index = Math.floorMod(timestampSeconds, stickerUrls.size.toLong()).toInt()
        return stickerUrls[index]
    }
}
