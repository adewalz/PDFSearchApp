package com.pdfsearch.app

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.regex.Pattern

/** Shows the full extracted text of one matched page, with every keyword bolded. */
class PageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PAGE_NUM = "extra_page_num"
        const val EXTRA_PAGE_TEXT = "extra_page_text"
        const val EXTRA_KEYWORDS = "extra_keywords"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page_viewer)

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val pageNum = intent.getIntExtra(EXTRA_PAGE_NUM, 0)
        val pageText = intent.getStringExtra(EXTRA_PAGE_TEXT).orEmpty()
        val keywords = intent.getStringArrayExtra(EXTRA_KEYWORDS)?.toList().orEmpty()

        title = getString(R.string.page_viewer_title_format, fileName, pageNum)

        val tvBody = findViewById<TextView>(R.id.tv_page_body)
        tvBody.text = highlight(pageText, keywords)
    }

    private fun highlight(text: String, keywords: List<String>): CharSequence {
        val spannable = SpannableString(text)
        for (kw in keywords) {
            val words = kw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val patternStr = if (words.size == 1) {
                "\\b" + Pattern.quote(words[0]) + "\\b"
            } else {
                "\\b" + words.joinToString("\\s+") { Pattern.quote(it) } + "\\b"
            }
            try {
                val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(text)
                while (matcher.find()) {
                    spannable.setSpan(StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), 0)
                }
            } catch (_: Exception) {
                // ignore malformed keyword patterns
            }
        }
        return spannable
    }
}
