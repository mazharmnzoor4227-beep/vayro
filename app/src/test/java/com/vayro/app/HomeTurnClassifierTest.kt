package com.vayro.app

import com.vayro.app.ui.HomeTurnClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.38 (Part N) regression battery for the home-chat classifier.
 *
 * The user's two live misfires (v3.2.37, home thread, OpenRouter) lead
 * the conversation set: plain Hindi questions that were answered with
 * the "This is a job for Work" card. Questions must ALWAYS classify as
 * conversation; only unmistakable creation commands classify as tasks.
 */
class HomeTurnClassifierTest {

    private fun chat(text: String) =
        assertTrue("should be CONVERSATION: $text", HomeTurnClassifier.isConversational(text))

    private fun task(text: String) =
        assertFalse("should be TASK: $text", HomeTurnClassifier.isConversational(text))

    @Test
    fun userLiveMisfiresAreConversation() {
        // His exact sentences from the v3.2.37 screenshot.
        chat("मैं सिर्फ पूछ रहा हूं कि तुम क्या-क्या कर सकते हो")
        chat("अच्छा तुम्हारा नाम क्या है यह बताओ")
    }

    @Test
    fun questionsInEveryLanguageAreConversation() {
        chat("what is your name")
        chat("What can you do?")
        chat("who are you")
        chat("how does this app work")
        chat("can you explain what an API is")
        chat("tum kaun ho")
        chat("tum kon ho")
        chat("aap ka naam kya hai")
        chat("ye kaise kaam karta hai")
        chat("meri website kaisi hai")
        chat("mujhe samjhao ye error kya hai")
        chat("samjhao recursion kya hota hai")
        chat("just asking — tum online ho kya")
        chat("क्या तुम मेरा नाम जानते हो")
        chat("یہ کیسے کام کرتا ہے")
        chat("hello")
        chat("hi, good morning")
        chat("thanks!")
        chat("Write a poem about rain")
        chat("fix the login bug")
    }

    @Test
    fun genuineCreationCommandsAreTasks() {
        task("app banao")
        task("ek game bana do")
        task("ek app banao jo weather dikhaye")
        task("मेरे लिए एक website बना दो")
        task("build a landing page")
        task("build me a snake game")
        task("create a file named notes.txt")
        task("fix the crash in MainViewModel.kt")
        task("ye file delete kar do")
        task("update my app icon")
        task("deploy this to github")
        task("ایک ایپ بناؤ")
    }

    @Test
    fun pdfZipDeliveryRequestsAreTasks() {
        // v3.2.43 (P3c): file-production asks — verb + format, and the
        // give-form (format + give verb) — in every script the user
        // writes. These must reach Work so real files get produced.
        task("Give me this as a PDF")
        task("give me the master prompt as a zip")
        task("convert this into a PDF file")
        task("export this chat as a pdf")
        task("is master prompt ki PDF bana do")
        task("master prompt ko zip kar do")
        task("yeh wali guide PDF mein de do")
        task("मास्टर प्रॉम्प्ट की पीडीएफ बना दो")
        task("اس کی پی ڈی ایف بنا دو")
        task("create a file named master-prompt.pdf")
    }

    @Test
    fun pdfZipQuestionsStayConversation() {
        // Rule 2 still wins: asking ABOUT the formats is chat, and the
        // polite question form stays in home (standing classifier law).
        chat("what is a PDF file?")
        chat("PDF kya hota hai")
        chat("kya tum pdf bana sakte ho?")
        chat("zip file kaise khulti hai")
        chat("can you explain what a zip archive is")
    }

    @Test
    fun attachmentsAndPathsAreTasks() {
        assertFalse(
            HomeTurnClassifier.isConversational("hello", hasAttachments = true),
        )
        assertFalse(
            HomeTurnClassifier.isConversational(
                "look at this",
                mentionedPaths = listOf("/src/Main.kt"),
            ),
        )
    }
}
