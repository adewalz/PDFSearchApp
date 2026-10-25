package com.pdfsearch.app

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ResultsAdapter(
    private val items: MutableList<PdfResult>,
    private val onStateChanged: () -> Unit,
    private val onOpenPdf: (PdfResult) -> Unit,
    private val onUnlockRequested: (PdfResult) -> Unit
) : RecyclerView.Adapter<ResultsAdapter.VH>() {

    private val expanded = mutableSetOf<Int>()
    private var filterText: String = ""

    // index into `items` after filtering
    private var visibleIndices: List<Int> = items.indices.toList()

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val header: View = root.findViewById(R.id.r_header)
        val fileName: TextView = root.findViewById(R.id.r_filename)
        val meta: TextView = root.findViewById(R.id.r_meta)
        val badge: TextView = root.findViewById(R.id.r_badge)
        val body: LinearLayout = root.findViewById(R.id.r_body)
        val kwContainer: LinearLayout = root.findViewById(R.id.r_kw_container)
        val btnSave: View = root.findViewById(R.id.btn_save)
        val btnSkip: View = root.findViewById(R.id.btn_skip)
        val btnReset: View = root.findViewById(R.id.btn_reset)
        val btnOpenPdf: View = root.findViewById(R.id.btn_open_pdf)
        val btnUnlock: View = root.findViewById(R.id.btn_unlock)
        val statusNote: TextView = root.findViewById(R.id.r_status_note)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = visibleIndices.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val realIndex = visibleIndices[position]
        val r = items[realIndex]
        val ctx = holder.root.context

        holder.fileName.text = r.fileName
        val sizeStr = if (r.sizeKb > 1024) "%.1f MB".format(r.sizeKb / 1024) else "%.0f KB".format(r.sizeKb)
        holder.meta.text = ctx.getString(
            R.string.result_meta_format,
            sizeStr, r.timeSec, r.results.size, r.totalPageHits
        )

        // Badge + explanatory note — priority: saved > status issue
        when {
            r.saved -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = ctx.getString(R.string.badge_saved)
                holder.badge.setBackgroundResource(R.drawable.badge_saved_bg)
            }
            r.status == PdfStatus.PASSWORD_PROTECTED -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = ctx.getString(R.string.badge_locked)
                holder.badge.setBackgroundResource(R.drawable.badge_warn_bg)
            }
            r.status == PdfStatus.NO_TEXT_LAYER -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = ctx.getString(R.string.badge_scanned)
                holder.badge.setBackgroundResource(R.drawable.badge_warn_bg)
            }
            r.status == PdfStatus.ERROR -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = ctx.getString(R.string.badge_error)
                holder.badge.setBackgroundResource(R.drawable.badge_error_bg)
            }
            else -> holder.badge.visibility = View.GONE
        }

        when (r.status) {
            PdfStatus.PASSWORD_PROTECTED -> {
                holder.statusNote.visibility = View.VISIBLE
                holder.statusNote.text = ctx.getString(R.string.note_password_protected)
                holder.btnUnlock.visibility = View.VISIBLE
            }
            PdfStatus.NO_TEXT_LAYER -> {
                holder.statusNote.visibility = View.VISIBLE
                holder.statusNote.text = ctx.getString(R.string.note_no_text_layer)
                holder.btnUnlock.visibility = View.GONE
            }
            PdfStatus.ERROR -> {
                holder.statusNote.visibility = View.VISIBLE
                holder.statusNote.text = r.error ?: ctx.getString(R.string.badge_error)
                holder.btnUnlock.visibility = View.GONE
            }
            else -> {
                holder.statusNote.visibility = View.GONE
                holder.btnUnlock.visibility = View.GONE
            }
        }

        val isOpen = expanded.contains(realIndex)
        holder.body.visibility = if (isOpen) View.VISIBLE else View.GONE
        holder.header.setOnClickListener {
            if (isOpen) expanded.remove(realIndex) else expanded.add(realIndex)
            notifyItemChanged(position)
        }

        // Build keyword -> page -> snippet blocks
        holder.kwContainer.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for ((kw, hits) in r.results) {
            val kwLabel = TextView(ctx).apply {
                text = ctx.getString(R.string.kw_label_format, kw)
                setTextAppearance(R.style.KeywordLabel)
                setPadding(0, 24, 0, 8)
            }
            holder.kwContainer.addView(kwLabel)

            for (pg in hits.pages.sorted()) {
                val row = inflater.inflate(R.layout.item_page_hit, holder.kwContainer, false)
                val pageNum = row.findViewById<TextView>(R.id.page_num)
                val snippetView = row.findViewById<TextView>(R.id.page_snippet)
                pageNum.text = ctx.getString(R.string.page_prefix, pg)
                val snippets = hits.snippets[pg].orEmpty()
                snippetView.text = buildHighlighted(snippets.joinToString("\n"))
                row.setOnClickListener {
                    val fullText = r.pageTexts[pg].orEmpty()
                    val intent = Intent(ctx, PageViewerActivity::class.java).apply {
                        putExtra(PageViewerActivity.EXTRA_FILE_NAME, r.fileName)
                        putExtra(PageViewerActivity.EXTRA_PAGE_NUM, pg)
                        putExtra(PageViewerActivity.EXTRA_PAGE_TEXT, fullText)
                        putExtra(PageViewerActivity.EXTRA_KEYWORDS, r.results.keys.toTypedArray())
                    }
                    ctx.startActivity(intent)
                }
                holder.kwContainer.addView(row)
            }
        }

        holder.btnSave.setOnClickListener {
            r.saved = true
            onStateChanged()
            notifyItemChanged(position)
        }
        holder.btnSkip.setOnClickListener {
            r.saved = false
            items.removeAt(realIndex)
            applyFilter(filterText)
            onStateChanged()
        }
        holder.btnReset.setOnClickListener {
            r.saved = false
            onStateChanged()
            notifyItemChanged(position)
        }
        holder.btnOpenPdf.setOnClickListener { onOpenPdf(r) }
        holder.btnUnlock.setOnClickListener { onUnlockRequested(r) }
    }

    /** Converts our **bold** snippet markers into a real bold SpannableString. */
    private fun buildHighlighted(text: String): CharSequence {
        val builder = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("**", i)
            if (start == -1) {
                builder.append(text.substring(i))
                break
            }
            builder.append(text.substring(i, start))
            val end = text.indexOf("**", start + 2)
            if (end == -1) {
                builder.append(text.substring(start))
                break
            }
            val boldPart = text.substring(start + 2, end)
            val boldStart = builder.length
            builder.append(boldPart)
            builder.setSpan(StyleSpan(Typeface.BOLD), boldStart, builder.length, 0)
            i = end + 2
        }
        return builder
    }

    fun setData(newItems: List<PdfResult>) {
        items.clear()
        items.addAll(newItems)
        applyFilter(filterText)
    }

    fun appendOne(result: PdfResult) {
        items.add(result)
        applyFilter(filterText)
    }

    fun applyFilter(query: String) {
        filterText = query
        // Show real matches *and* files that need the user's attention (locked / scanned /
        // errored) — otherwise a password-protected or scanned PDF just silently vanishes
        // from the list, which is exactly the "looks like it has no matches" problem we're
        // trying to avoid.
        visibleIndices = items.indices.filter { idx ->
            val item = items[idx]
            (item.hasMatches || item.needsAttention) &&
                (query.isBlank() || item.fileName.lowercase().contains(query.lowercase()))
        }
        notifyDataSetChanged()
    }

    fun savedResults(): List<PdfResult> = items.filter { it.saved }

    fun matchedCount(): Int = items.count { it.hasMatches }

    fun attentionCount(): Int = items.count { it.needsAttention }
}
