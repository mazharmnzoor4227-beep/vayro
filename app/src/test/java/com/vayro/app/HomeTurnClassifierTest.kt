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
