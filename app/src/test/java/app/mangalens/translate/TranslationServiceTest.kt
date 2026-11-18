package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Bubble
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Translation is the AI's alone. When the AI fails, the failure is what
 * comes back — never some other translation in its place, which the reader
 * could not tell from the AI's own — and the failure keeps its type, so the
 * pill can say whether it was the key, the network or the rate limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationServiceTest {

    private var server: FakeHttpServer? = null

    @Before
    fun setUp() {
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    @After
    fun tearDown() {
        server?.close()
        GeminiApi.base = GeminiApi.BASE
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    private fun serve(handler: (FakeHttpServer.Exchange) -> Unit): FakeHttpServer =
        FakeHttpServer(handler).also {
            server = it
            GeminiApi.base = it.base
        }

    private fun custom(srv: FakeHttpServer) =
        AppSettings(provider = LlmProvider.CUSTOM, model = "m", customUrl = srv.base + "chat", apiKey = "k")

    @Test
    fun aContinuingAddressLeavesAnUnconfirmedSpeakerUnnamed() {
        val prior = "Rider: Chestnut! You're carrying me to the village?"
        assertEquals("", VisionLlmEngine.speakerAfterAddress(prior, "You brought my bag, too.", "Chestnut"))
        assertEquals("Rider", VisionLlmEngine.speakerAfterAddress(prior, "You brought my bag, too.", "Rider"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress(prior, "Yes, I'm taking you home.", "Chestnut"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress(prior, "You called. I'm Chestnut.", "Chestnut"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress(prior, "You want me to carry that bag?", "Chestnut"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress(prior, "You can carry your own bag.", "Chestnut"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress(prior, "You called me. My name is Chestnut.", "Chestnut"))
        assertEquals("Mina", VisionLlmEngine.speakerAfterAddress("— Wait here.", "You need the map.", "Mina"))
        assertEquals("Chestnut", VisionLlmEngine.speakerAfterAddress("— Chestnut is ready!", "You brought a bag.", "Chestnut"))
    }

    @Test
    fun aDirectlyCalledNameCannotBecomeTheSpeakerInStoryMemory() {
        val source = "밤톨아! 이런 용감한 아이~"
        assertEquals("", VisionLlmEngine.attributedSpeaker(source, "Chestnut! You're so brave.", "chestnut", ""))
        assertEquals("", VisionLlmEngine.attributedSpeaker(source, "Hey, you brave little one!", "Chestnut", "Chestnut"))
        assertEquals("The rider", VisionLlmEngine.attributedSpeaker(source, "Chestnut! You're so brave.", "The rider", "Chestnut"))
        assertEquals("Chestnut", VisionLlmEngine.attributedSpeaker("나는 밤톨이야.", "I'm Chestnut.", "Chestnut", ""))
        assertEquals("Mina", VisionLlmEngine.attributedSpeaker("미나가 왔어!", "Mina is here!", "Mina", ""))
    }

    @Test
    fun anIncorrectJapaneseRequestCannotStreamOrEnterStoryMemory() = runBlocking {
        val source = "まだ箱を開けないで。昼までここで待って。"
        val wrong = "I'm not opening the box yet. Wait here until noon."
        val correct = "Don't open the box yet. Wait here until noon."
        val other = "The key is here."
        val srv = serve { ex ->
            val messages = JSONObject(ex.body).getJSONArray("messages")
            if (messages.getJSONObject(1).optJSONArray("content") != null) {
                val bubbles = JSONArray()
                    .put(JSONObject().put("id", 0).put("src", source).put("en", wrong))
                    .put(JSONObject().put("id", 1).put("src", "鍵はここにある。").put("en", other))
                val delta = JSONObject().put("content", JSONObject().put("bubbles", bubbles).toString())
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
                ex.respond(200, "data: $event\n\ndata: [DONE]\n\n", "text/event-stream")
            } else {
                val edits = JSONArray().put(JSONObject().put("id", 0).put("en", correct))
                    .put(JSONObject().put("id", 1).put("en", "Unrequested change."))
                val msg = JSONObject().put("content", JSONObject().put("items", edits).toString())
                ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg))).toString())
            }
        }
        val image = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val anchors = listOf(Bubble(source, Rect(12, 25, 295, 120), false),
                Bubble("鍵はここにある。", Rect(24, 155, 278, 240), false))
            val partials = mutableListOf<String>()
            val final = VisionLlmEngine(custom(srv)).translatePage(image, SourceLang.JA, anchors) { partials += it.en }
            assertEquals(listOf(other), partials)
            assertEquals(listOf(correct, other), final.map { it.en })
            assertEquals(listOf("— $correct", "— $other"), StoryContext.snapshot())
            assertEquals(2, srv.exchanges.size)
            val message = JSONObject(srv.exchanges.last().body).getJSONArray("messages").getJSONObject(1).getString("content")
            val items = JSONObject(message.substringAfter("\n\n")).getJSONArray("items")
            assertEquals(1, items.length())
            assertEquals(source, items.getJSONObject(0).getString("source"))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun anUnavailableJapaneseReviewFallsBackOnlyForTheReversedRequest() = runBlocking {
        fun line(src: String, en: String) = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, src, en, false)
        val connective = line("あきらめないで探した。", "I don't give up and keep looking.")
        val ownPlan = line("私はこの箱を開けない。", "I'm not opening this box.")
        val faithful = line("走らないでください。", "Please don't run.")
        val wrong = line("動かないでください。", "I won't move.")
        val cards = mutableListOf(connective, wrong, ownPlan, faithful)
        val srv = serve { it.respond(503, "unavailable") }
        val reader = VisionLlmEngine(custom(srv))
        reader.reviewDialogueRoles(cards, "{}", JSONArray())
        assertEquals(listOf(connective, ownPlan, faithful), cards)
        reader.reviewDialogueRoles(cards, "{}", JSONArray())
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun aKoreanPossessiveDoesNotTurnTheOwnerIntoTheCompanionsActionSubject() = runBlocking {
        fun line(src: String, en: String) = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, src, en, false)
        val prior = "Guide: Ash, are you taking me to camp?"
        val wrong = line("내 가방도 챙겨 왔네.", "I brought my bag along, too.")
        val correct = "You brought my bag along, too."
        val explicit = line("내 가방은 내가 가져왔네.", "I brought my bag.")
        val observer = line(wrong.src, "I see you brought my bag, too.")
        val srv = serve { ex ->
            val edits = JSONArray().put(JSONObject().put("id", 0).put("actor", "listener").put("en", wrong.en))
            val partlyMalformed = "{\"items\":" + edits + "-lnd\nuser\n{\"items\":[{\"id\":0,\"actor\":\"speaker\"}]}"
            val msg = JSONObject().put("content", partlyMalformed)
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg))).toString())
        }
        val reader = VisionLlmEngine(custom(srv))
        val noHistory = mutableListOf(wrong)
        reader.reviewDialogueRoles(noHistory, "{}", JSONArray())
        assertEquals(listOf(wrong), noHistory)
        assertTrue(srv.exchanges.isEmpty())
        val cards = mutableListOf(wrong, explicit, observer)
        reader.reviewDialogueRoles(cards, "{}", JSONArray().put(prior))
        assertEquals(listOf(correct, explicit.en, observer.en), cards.map { it.en })
        val message = JSONObject(srv.exchanges.single().body).getJSONArray("messages").getJSONObject(1).getString("content")
        val items = JSONObject(message.substringAfter("\n\n")).getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals(prior, items.getJSONObject(0).getString("previous_line"))
        assertEquals(false, items.getJSONObject(0).has("draft"))
        assertEquals("dialogue_roles", JSONObject(message.substringAfter("\n\n")).getString("review"))
        assertEquals("You’ve already packed my books, too.",
            VisionLlmEngine.listenerHandledPossessions("I’ve already packed my books, too."))
        assertEquals("…You brought our bags.", VisionLlmEngine.listenerHandledPossessions("…We brought our bags."))
        assertEquals(observer.en, VisionLlmEngine.listenerHandledPossessions(observer.en))
    }

    @Test
    fun aConfirmedSpeakerOrNamedThirdPartyDoesNotBecomeTheListener() = runBlocking {
        val own = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, "내 사진도 챙겨 왔네.", "I've packed my photos, too.", false)
        val third = VisionLlmEngine.VisionBubble(1, 0, 0, 0, 0, "내 우산이랑 지도까지 민지가 가져왔네.",
            "I brought my umbrella and even my map.", false)
        val correct = "Minji brought my umbrella and even my map."
        val srv = serve { ex ->
            val edits = JSONArray().put(JSONObject().put("id", 0).put("actor", "speaker"))
                .put(JSONObject().put("id", 1).put("actor", "third_person").put("en", correct))
            val msg = JSONObject().put("content", JSONObject().put("items", edits).toString())
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg))).toString())
        }
        val cards = mutableListOf(own, third)
        VisionLlmEngine(custom(srv)).reviewDialogueRoles(cards, "{}",
            JSONArray().put("Guide: Ash, are you taking me to camp?"))
        assertEquals(listOf(own.en, correct), cards.map { it.en })
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun aListOfExtraPossessionsKeepsTheEstablishedHelperAndTheOriginalOwner() = runBlocking {
        val source = "내 지도랑 등불까지 챙겨 왔네."
        val draft = "I packed my map and even the lamp..."
        val prior = "Guide: Star! Are you carrying me to the station?"
        val line = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, source, draft, false)
        val srv = serve { it.respond(503, "No text review for a continuing list or an explicit speaker.") }
        val reader = VisionLlmEngine(custom(srv))
        val list = mutableListOf(line)
        reader.reviewDialogueRoles(list, "{}", JSONArray().put(prior))
        assertEquals("You packed my map and even the lamp...", list.single().en)
        val explicit = mutableListOf(line.copy(src = "내 지도랑 등불까지 내가 챙겨 왔네."))
        reader.reviewDialogueRoles(explicit, "{}", JSONArray().put(prior))
        assertEquals(draft, explicit.single().en)
        reader.reviewDialogueRoles(mutableListOf(line), "{}", JSONArray().put("Guide: I packed my own luggage."))
        assertTrue(srv.exchanges.isEmpty())
    }

    @Test
    fun disputedAddressesUseOneTextRequestAndLeaveOtherBalloonsIntact() = runBlocking {
        fun bubble(src: String, en: String) = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, src, en, false, "")
        val first = bubble("봄이야! 이런 장난스러운 녀석~ 또 날 기다렸어?", "Spring! This playful little thing! Were you waiting for me?")
        val second = bubble("눈송이야! 요런 예쁜 것~ 새로 산 인형이야?", "Snowflake! This lovely little thing! Is it a new doll?")
        val direct = bubble(first.src, "Spring! You little rascal! Were you waiting for me?")
        val ordinary = bubble("수연아! 이 장난감은 누구 거야?", "Suyeon! Whose toy is this cute thing?")
        val cards = mutableListOf(first, second, direct, ordinary)
        val srv = serve { ex ->
            val edits = JSONArray()
                .put(JSONObject().put("id", 0).put("referent", "listener").put("en", direct.en)
                    .put("name", "Spring"))
                .put(JSONObject().put("id", 1).put("referent", "object"))
                .put(JSONObject().put("id", 2).put("referent", "listener").put("en", "Unrequested change"))
            val body = JSONObject().put("items", edits)
            val msg = JSONObject().put("content", body.toString())
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg))).toString())
        }
        val reader = VisionLlmEngine(custom(srv))
        val terms = JSONObject()
        reader.reviewFormsOfAddress(cards, "{}", JSONArray().put("Wait here."), terms)
        assertEquals(listOf(direct.en, second.en, direct.en, ordinary.en), cards.map { it.en })
        assertEquals("Spring", terms.getString("봄이"))
        assertTrue(!terms.has("엉뚱"))
        val message = JSONObject(srv.exchanges.single().body).getJSONArray("messages").getJSONObject(1).getString("content")
        val request = JSONObject(message.substringAfter("\n\n"))
        assertTrue(!message.contains("base64"))
        assertEquals(2, request.getJSONArray("items").length())
        assertEquals("Wait here.", request.getJSONArray("story_so_far").getString(0))
        reader.reviewFormsOfAddress(mutableListOf(direct, ordinary), "{}", JSONArray(), terms)
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun localVocativeLetteringRequiresAgreementAndPreservesSeparateObjects() {
        val source = "별아! 이런 용감한 녀석~ 나를 도와주는 거야?"
        val praise = "Star! This brave thing~ Are you helping me home?"
        val expected = "Star! You're so brave~ Are you helping me home?"
        assertEquals(expected, VisionLlmEngine.spokenAddress(source, praise, "", ""))
        assertEquals(praise, VisionLlmEngine.spokenAddress(source, praise, "object", "Star"))
        assertEquals(praise, VisionLlmEngine.spokenAddress(source, praise, "unclear", ""))
        val doll = "Star! This brave thing~ Are you taking me to see it?"
        assertEquals(doll, VisionLlmEngine.spokenAddress(source, doll, "", ""))
        val separate = "Star! This brave thing is the toy you bought me."
        assertEquals(separate, VisionLlmEngine.spokenAddress(source, separate, "listener", "Star"))
        assertEquals("Chestnut! You're really brave! Did you stay?",
            VisionLlmEngine.spokenAddress("밤톨아! 이런 용감한 아이~ 남았어?",
                "You! This is really brave! Did you stay?", "listener", "Chestnut"))
        val natural = "Star! You little rascal! Are you waiting for me?"
        assertEquals(natural, VisionLlmEngine.spokenAddress(source, natural, "listener", ""))
    }

    @Test
    fun aNamedListenerMissingFromAnEnglishCalloutReceivesAReview() = runBlocking {
        val source = "밤톨아! 이런 용감한 녀석~ 날 지켜준 거야?"
        val spoken = "Chestnut! You're so brave~ Were you protecting me?"
        val srv = serve { ex ->
            val item = JSONObject().put("id", 0).put("referent", "listener").put("name", "Chestnut")
            val msg = JSONObject().put("content", JSONObject().put("items", JSONArray().put(item)).toString())
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg))).toString())
        }
        val cards = mutableListOf(VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0, source,
            "You! This is so brave~ Were you protecting me?", false, ""))
        val terms = JSONObject()
        VisionLlmEngine(custom(srv)).reviewFormsOfAddress(cards, "{}", JSONArray(), terms)
        assertEquals(spoken, cards.single().en)
        assertEquals("Chestnut", terms.getString("밤톨"))
        val content = JSONObject(srv.exchanges.single().body).getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals(false, JSONObject(content.substringAfter("\n\n")).getJSONArray("items").getJSONObject(0).getBoolean("rewrite"))
    }

    @Test
    fun anUnavailableAddressReviewKeepsTheOriginalImageTranslation() = runBlocking {
        val srv = serve { ex -> ex.respond(503, "unavailable") }
        val original = VisionLlmEngine.VisionBubble(0, 0, 0, 0, 0,
            "별이야! 이런 다정한 아이~ 날 기다렸구나!", "Star! That gentle little thing! You waited for me!", false, "")
        val cards = mutableListOf(original)
        VisionLlmEngine(custom(srv)).reviewFormsOfAddress(cards, "{}", JSONArray(), JSONObject())
        assertEquals(listOf(original), cards)
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun copiedSchemaValuesAreRetriedAndNeverShownEvenFromAnOlderCache() = runBlocking {
        var answer = "<English>"
        val srv = serve { ex ->
            val bubbles = JSONArray().put(JSONObject().put("id", 0).put("en", answer))
            val message = JSONObject().put("content", JSONObject().put("bubbles", bubbles).toString())
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString())
        }
        val settings = custom(srv)
        val namespace = LlmEngine(settings).cacheNamespace
        val source = listOf("괜찮아요")
        val cache = TranslationCache()
        val key = TranslationCache.key(namespace, "KO", source.single())
        cache.put(key, "<English>")
        val service = TranslationService(cache)
        assertTrue(!service.fullyCached(namespace, SourceLang.KO, source))
        assertEquals(listOf(""), service.translate(source, SourceLang.KO, settings).texts)
        assertEquals(null, JunkFilter.accept(source.single(), "<English>"))
        answer = "I study English."
        assertEquals(listOf(answer), service.translate(source, SourceLang.KO, settings).texts)
        assertEquals(answer, cache.get(key))
        assertEquals(2, srv.exchanges.size)
    }

    private val lines = listOf("괜찮아.", "내가 지켜줄게.")

    @Test
    fun `an AI that fails is reported, not answered in other words`() = runBlocking {
        val srv = serve { ex -> ex.respond(500, "{\"error\":\"down\"}") }
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, custom(srv))
            fail("expected the AI's failure")
        } catch (e: RuntimeException) {
            assertTrue(e.message, e.message.orEmpty().contains("HTTP 500"))
        }
        // The AI was asked once, and nothing else was asked at all.
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun `a gemini rate limit comes back as itself`() = runBlocking {
        val srv = serve { ex ->
            ex.respond(429, "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}")
        }
        val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k", model = "gemini-flash-latest")
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, settings)
            fail("expected the rate limit")
        } catch (e: GeminiRateLimited) {
            // The type the status pill names the cause by.
        }
        assertTrue(srv.exchanges.isNotEmpty())
    }

    @Test
    fun `no key fails before anything is sent`() = runBlocking {
        val srv = serve { ex -> ex.respond(500, "{}") }
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, AppSettings(apiKey = ""))
            fail("expected no key")
        } catch (e: RuntimeException) {
            assertEquals("No API key set for Gemini", e.message)
        }
        assertEquals(0, srv.exchanges.size)
    }

    @Test
    fun `the AI's answers come back in its own words and are kept for the next pass`() = runBlocking {
        val reply = JSONObject().put(
            "bubbles",
            JSONArray()
                .put(JSONObject().put("id", 0).put("en", "It's okay.").put("kind", "dialogue"))
                .put(JSONObject().put("id", 1).put("en", "I'll protect you.").put("kind", "dialogue")),
        ).toString()
        val srv = serve { ex ->
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply)))).toString())
        }
        val service = TranslationService(TranslationCache())
        val first = service.translate(lines, SourceLang.KO, custom(srv))
        assertEquals(listOf("It's okay.", "I'll protect you."), first.texts)
        assertEquals("Custom AI", first.engineLabel)
        val again = service.translate(lines, SourceLang.KO, custom(srv))
        assertEquals(first.texts, again.texts)
        assertEquals(1, srv.exchanges.size)
    }
    @Test
    fun `stable cached lines paint before the request and split sentences wait for their context`() = runBlocking {
        val srv = serve { ex ->
            val bubbles = JSONArray()
                .put(JSONObject().put("id", 0).put("en", "Stay here."))
                .put(JSONObject().put("id", 1).put("en", "I'll be back."))
            val reply = JSONObject().put("bubbles", bubbles).toString()
            ex.respond(200, JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", reply))
            )).toString())
        }
        val s = custom(srv)
        val cache = TranslationCache()
        val texts = listOf("괜찮아요", "여기 있어요", "곧 올게요")
        fun key(i: Int) = TranslationCache.key(LlmEngine(s).cacheNamespace, "KO", texts[i])
        cache.put(key(0), "It's okay.")
        cache.put(key(1), "Previous wording.")
        val frames = mutableListOf<Map<Int, String>>()
        val outcome = TranslationService(cache).translate(texts, SourceLang.KO, s,
            runs = listOf(-1, 7, 7), parts = listOf(0, 0, 1), onProgress = {
                if (frames.isEmpty()) assertTrue("first paint precedes the request", srv.exchanges.isEmpty())
                frames += it
            })
        assertEquals(mapOf(0 to "It's okay."), frames.first())
        assertEquals(listOf("It's okay.", "Stay here.", "I'll be back."), outcome.texts)
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun `Korean word boundaries distinguish meanings and repeated spaces preserve a hit`() {
        fun key(text: String) = TranslationCache.key("fixture", "KO", text)
        val enteringRoom = "아버지가 방에 들어가신다"
        val enteringBag = "아버지 가방에 들어가신다"
        val cache = TranslationCache()
        cache.put(key(enteringRoom), "Father enters the room.")
        assertEquals(null, cache.get(key(enteringBag)))
        assertEquals("Father enters the room.", cache.get(key("  아버지가   방에\n들어가신다  ")))
    }
}
