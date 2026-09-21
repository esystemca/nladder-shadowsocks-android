/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.ViewCompat
import com.github.shadowsocks.auth.AuthManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.MainListListener
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.android.material.button.MaterialButton

class AboutFragment : ToolbarFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListHolderListener)
        toolbar.title = getString(R.string.about_title, BuildConfig.VERSION_NAME)

        val logoutButton = view.findViewById<MaterialButton>(R.id.btn_logout)
        if (DataStore.isLoggedIn) {
            logoutButton.visibility = View.VISIBLE
            logoutButton.setOnClickListener { showLogoutConfirmation() }
        } else {
            logoutButton.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.tv_about).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this, MainListListener)
            text = SpannableStringBuilder(resources.openRawResource(R.raw.about).bufferedReader().readText()
                    .parseAsHtml(HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM)).apply {
                val density = resources.displayMetrics.density
                val stripeWidth = (4 * density).toInt()
                val gapWidth = (12 * density).toInt()
                for (span in getSpans(0, length, QuoteSpan::class.java)) {
                    val start = getSpanStart(span)
                    val end = getSpanEnd(span)
                    val flags = getSpanFlags(span)
                    removeSpan(span)
                    setSpan(CustomQuoteSpan(0xFF007E3A.toInt(), stripeWidth, gapWidth), start, end, flags)
                }

                for (span in getSpans(0, length, URLSpan::class.java)) {
                    setSpan(object : ClickableSpan() {
                        override fun onClick(view: View) = when {
                            span.url == "#logout" || span.url == "logout" -> {
                                showLogoutConfirmation()
                            }
                            span.url.startsWith("#") -> {
                                startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                            }
                            span.url.startsWith("mailto:") -> {
                                startActivity(Intent.createChooser(Intent().apply {
                                    action = Intent.ACTION_SENDTO
                                    data = span.url.toUri()
                                }, getString(R.string.send_email)))
                            }
                            else -> (activity as MainActivity).launchUrl(span.url)
                        }
                    }, getSpanStart(span), getSpanEnd(span), getSpanFlags(span))
                    removeSpan(span)
                }
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun showLogoutConfirmation() {
        if (DataStore.isLoggedIn) {
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.logged_in_as, DataStore.userEmail ?: ""))
                .setPositiveButton(R.string.logout) { _, _ ->
                    AuthManager.logout()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startActivity(Intent(context, LoginActivity::class.java))
        }
    }
}

class CustomQuoteSpan(
    private val color: Int,
    private val stripeWidth: Int,
    private val gapWidth: Int
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = stripeWidth + gapWidth

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        val style = p.style
        val paintColor = p.color

        p.style = Paint.Style.FILL
        p.color = color

        val left = x.toFloat()
        val right = (x + dir * stripeWidth).toFloat()
        c.drawRect(
            kotlin.math.min(left, right),
            top.toFloat(),
            kotlin.math.max(left, right),
            bottom.toFloat(),
            p
        )

        p.style = style
        p.color = paintColor
    }
}

