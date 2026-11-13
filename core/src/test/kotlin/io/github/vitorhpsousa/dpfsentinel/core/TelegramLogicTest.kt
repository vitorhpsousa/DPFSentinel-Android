package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramLogicTest {
    private val t = TelegramLogic

    @Test fun chatIdIsTidied() {
        assertEquals("123456789", t.normaliseChatId("123456789"))
        assertEquals("123456789", t.normaliseChatId("  123 456 789\n"))
        assertEquals("123456789", t.normaliseChatId("123 456 789"))          // NBSP, narrow NBSP
        assertEquals("123456789", t.normaliseChatId("​123456789﻿"))          // zero-width, BOM
        assertEquals("123456789", t.normaliseChatId("‎123,456,789"))
        assertEquals("123456789", t.normaliseChatId("123.456.789"))
        assertEquals("123456789", t.normaliseChatId("+123456789"))
        assertEquals("-1001234567890", t.normaliseChatId("-100 1234567890"))
        assertEquals("-1001234567890", t.normaliseChatId("−1001234567890"))       // typographic minus
        assertEquals("@my_channel", t.normaliseChatId(" @my_channel "))
    }

    @Test fun badChatIdsAreRefused() {
        for (bad in listOf(null, "", "   ", "abc", "12a34", "--5", "-", "+", "@", "@ab", "@1channel", "@has-dash", "12:34", "0", "0123",
            "99999999999999999999999", "123456:ABC-DEF")) {
            assertNull(t.normaliseChatId(bad), "should refuse '$bad'")
        }
    }

    @Test fun botIdTypedAsChatIdIsSpotted() {
        val token = "7123456789:AAHk3-exampleExampleExample_0123456789"
        assertTrue(t.looksLikeBotIdFromToken("7123456789", token))
        assertTrue(t.looksLikeBotIdFromToken(" 7 123 456 789 ", " $token\n"))
        assertFalse(t.looksLikeBotIdFromToken("123456789", token))
        assertFalse(t.looksLikeBotIdFromToken("-7123456789", token))
        assertFalse(t.looksLikeBotIdFromToken("7123456789", "not a token"))
        assertFalse(t.looksLikeBotIdFromToken("7123456789", ""))
        assertFalse(t.looksLikeBotIdFromToken("", token))
        assertFalse(t.looksLikeBotIdFromToken(null, null))
    }

    @Test fun errorsBecomeAdvice() {
        fun err(code: Int, desc: String, extra: String = "") = """{"ok":false,"error_code":$code,"description":"$desc"$extra}"""
        assertEquals("That bot token isn't valid — copy it again from @BotFather", t.describeError(401, err(401, "Unauthorized")))
        assertEquals("That bot token isn't valid — copy it again from @BotFather", t.describeError(404, err(404, "Not Found")))
        val start = "Open your bot in Telegram and press Start, then try again"
        assertEquals(start, t.describeError(400, err(400, "Bad Request: chat not found")))
        assertEquals(start, t.describeError(403, err(403, "Forbidden: bot can't initiate conversation with a user")))
        assertEquals("You blocked the bot — unblock it in Telegram", t.describeError(403, err(403, "Forbidden: bot was blocked by the user")))
        assertEquals(
            "Telegram is rate-limiting, try again in 35 s",
            t.describeError(429, err(429, "Too Many Requests: retry after 35", ""","parameters":{"retry_after":35}""")),
        )
        assertEquals("Telegram is rate-limiting, try again in 7 s", t.describeError(429, err(429, "Too Many Requests: retry after 7")))
        assertEquals("Telegram is rate-limiting, try again in a minute", t.describeError(429, null))
        assertEquals("No internet connection", t.describeError(0, null))
        assertEquals("No internet connection", t.describeError(-1, "java.net.UnknownHostException"))
        // Anything else: Telegram's own words.
        assertEquals("Bad Request: message text is empty", t.describeError(400, err(400, "Bad Request: message text is empty")))
        assertEquals("Forbidden: bot is not a member of the channel chat", t.describeError(403, err(403, "Forbidden: bot is not a member of the channel chat")))
        assertEquals("Not Found: method not found", t.describeError(404, err(404, "Not Found: method not found")))
        // No usable body.
        assertEquals("Telegram error 502", t.describeError(502, "<html>Bad Gateway</html>"))
        assertEquals("Telegram error 500", t.describeError(500, null))
        assertEquals("That bot token isn't valid — copy it again from @BotFather", t.describeError(401, ""))
    }

    // Shaped like a real getUpdates answer: a private chat, a supergroup with a command addressed
    // to the bot, a channel post, an update with no message, and a user whose name tries to look like JSON.
    private val updates = """
        {"ok":true,"result":[
         {"update_id":913400101,
          "message":{"message_id":41,"from":{"id":5123456789,"is_bot":false,"first_name":"Ana","last_name":"Silva","language_code":"en"},
           "chat":{"id":5123456789,"first_name":"Ana","last_name":"Silva","type":"private"},
           "date":1794000000,"text":"/start","entities":[{"offset":0,"length":6,"type":"bot_command"}]}},
         {"update_id":913400102,
          "my_chat_member":{"chat":{"id":-1002233445566,"title":"Car logs","type":"supergroup"},
           "from":{"id":5123456789,"is_bot":false,"first_name":"Ana"},"date":1794000030,
           "old_chat_member":{"user":{"id":7123456789,"is_bot":true,"first_name":"DPF"},"status":"left"},
           "new_chat_member":{"user":{"id":7123456789,"is_bot":true,"first_name":"DPF"},"status":"member"}}},
         {"update_id":913400103,
          "message":{"message_id":7,"from":{"id":5123456789,"is_bot":false,"first_name":"Ana"},
           "chat":{"id":-1002233445566,"title":"Car logs 🚗","type":"supergroup"},
           "date":1794000060,"text":"/Status@DpfSentinelBot now please",
           "entities":[{"offset":0,"length":22,"type":"bot_command"}]}},
         {"update_id":913400104,
          "message":{"message_id":8,"from":{"id":4000000001,"is_bot":false,"first_name":"\"id\":999, \"chat\":{\"id\":111}","username":"tricky"},
           "chat":{"id":4000000001,"first_name":"\"id\":999, \"chat\":{\"id\":111}","username":"tricky","type":"private"},
           "date":1794000090,"text":"my \"id\": 42 is {\"chat\":{\"id\":-5}}"}},
         {"update_id":913400105,
          "channel_post":{"message_id":3,"sender_chat":{"id":-1009876543210,"title":"DPF feed","type":"channel"},
           "chat":{"id":-1009876543210,"title":"DPF feed","username":"dpf_feed","type":"channel"},
           "date":1794000120,"text":"hello"}},
         {"update_id":913400106,
          "message":{"message_id":42,"from":{"id":5123456789,"is_bot":false,"first_name":"Ana"},
           "chat":{"id":5123456789,"first_name":"Ana","last_name":"Silva","type":"private"},
           "date":1794000150,"photo":[{"file_id":"x","width":90,"height":90}]}}
        ]}
    """.trimIndent()

    @Test fun chatsAreFoundInGetUpdates() {
        val c = t.findChats(updates)
        assertEquals(listOf(5123456789L, -1002233445566L, 4000000001L, -1009876543210L, 5123456789L), c.map { it.id })
        assertEquals(listOf("private", "supergroup", "private", "channel", "private"), c.map { it.type })
        assertEquals("Ana Silva", c[0].name)
        assertEquals("Car logs 🚗", c[1].name)
        assertEquals("\"id\":999, \"chat\":{\"id\":111}", c[2].name)
        assertEquals("DPF feed", c[3].name)
        assertEquals(listOf(913400101L, 913400103L, 913400104L, 913400105L, 913400106L), c.map { it.updateId })
        assertEquals(1794000060L, c[1].dateEpochS)
        assertEquals("/start", c[0].text)
        assertEquals("my \"id\": 42 is {\"chat\":{\"id\":-5}}", c[2].text)
        assertEquals("", c[4].text)
        // Commands, with the @BotName a group adds taken off.
        assertEquals(listOf("/start", "/status", "", "", ""), c.map { it.command })

        val distinct = t.latestPerChat(c)
        assertEquals(listOf(-1002233445566L, 4000000001L, -1009876543210L, 5123456789L), distinct.map { it.id })
        assertEquals(913400106L, distinct.last().updateId)
    }

    @Test fun commandsLoseTheirBotSuffix() {
        assertEquals("/status", t.commandOf("/status"))
        assertEquals("/status", t.commandOf("/status@DpfSentinelBot"))
        assertEquals("/status", t.commandOf("  /STATUS@DpfSentinelBot extra words"))
        assertEquals("/oil_changed", t.commandOf("/oil_changed\nnow"))
        assertEquals("", t.commandOf("status"))
        assertEquals("", t.commandOf("/"))
        assertEquals("", t.commandOf("/@bot"))
        assertEquals("", t.commandOf("say /status"))
        assertEquals("", t.commandOf(""))
    }

    @Test fun largeAndOddIdsSurvive() {
        // 52-bit ids, exact; an id written as a float by some proxy; a string id is not an id.
        val body = """{"ok":true,"result":[
            {"update_id":1,"message":{"chat":{"id":4503599627370495,"type":"private","first_name":"Max"},"date":10}},
            {"update_id":2,"message":{"chat":{"id":-4503599627370495,"type":"group","title":"Min"},"date":11}},
            {"update_id":3,"message":{"chat":{"id":-1.001234567890E12,"type":"supergroup","title":"Float"},"date":12}},
            {"update_id":4,"message":{"chat":{"id":"77","type":"private"},"date":13}},
            {"update_id":5,"message":{"chat":{"id":12,"type":"private","username":"only_user"},"date":14}}]}"""
        val c = t.findChats(body)
        assertEquals(listOf(4503599627370495L, -4503599627370495L, -1001234567890L, 12L), c.map { it.id })
        assertEquals("@only_user", c.last().name)
    }

    @Test fun unreadableUpdatesGiveNothing() {
        assertEquals(emptyList(), t.findChats(null))
        assertEquals(emptyList(), t.findChats(""))
        assertEquals(emptyList(), t.findChats("""{"ok":true,"result":[]}"""))
        assertEquals(emptyList(), t.findChats("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))
        assertEquals(emptyList(), t.findChats("<html>502</html>"))
        assertEquals(emptyList(), t.findChats(updates.dropLast(40)))           // cut off mid-way
        assertEquals(emptyList(), t.findChats("[".repeat(5000)))                // absurd nesting
    }

    @Test fun staleness() {
        assertFalse(t.isStale(1000, 1000))
        assertFalse(t.isStale(1000, 1120))
        assertTrue(t.isStale(1000, 1121))
        assertFalse(t.isStale(1000, 990))            // phone clock a little behind
        assertTrue(t.isStale(0, 1_794_000_000))      // no date at all
        assertFalse(t.isStale(1000, 1500, maxAgeS = 600))
    }
}
