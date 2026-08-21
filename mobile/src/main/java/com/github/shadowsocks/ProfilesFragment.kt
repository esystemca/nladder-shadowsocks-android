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
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.util.LongSparseArray
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.*
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.fragment.showAllowingStateLoss
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.OpenJson
import com.github.shadowsocks.utils.SaveJson
import com.github.shadowsocks.utils.readableMessage
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.MainListListener
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import timber.log.Timber
import java.nio.charset.StandardCharsets

class ProfilesFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener, SearchView.OnQueryTextListener {
    companion object {
        /**
         * used for callback from stateChanged from MainActivity
         */
        var instance: ProfilesFragment? = null

        private const val KEY_URL = "com.github.shadowsocks.QRCodeDialog.KEY_URL"

        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    /**
     * Is ProfilesFragment editable at all.
     */
    private val isEnabled get() = (activity as MainActivity).state.let { it.canStop || it == BaseService.State.Stopped }

    private fun isProfileEditable(id: Long) =
            (activity as MainActivity).state == BaseService.State.Stopped || id !in Core.activeProfileIds

    class QRCodeDialog() : DialogFragment() {
        constructor(url: String) : this() {
            arguments = bundleOf(Pair(KEY_URL, url))
        }

        /**
         * Based on:
         * https://android.googlesource.com/platform/packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
         * https://android.googlesource.com/platform/packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
         */
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = try {
            val url = arguments?.getString(KEY_URL)!!
            val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)
            val hints = mutableMapOf<EncodeHintType, Any>()
            if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
            val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                setImageBitmap(Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                    for (x in 0 until size) for (y in 0 until size) {
                        setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                })
            }
        } catch (e: WriterException) {
            Timber.w(e)
            (activity as MainActivity).snackbar().setText(e.readableMessage).show()
            dismiss()
            null
        }
    }

    inner class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view),
            View.OnClickListener, PopupMenu.OnMenuItemClickListener {
        internal lateinit var item: Profile

        private val text1 = itemView.findViewById<TextView>(android.R.id.text1)
        private val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        private val traffic = itemView.findViewById<TextView>(R.id.traffic)
        private val countryCodeView = itemView.findViewById<TextView>(R.id.country_code)
        private val edit = itemView.findViewById<View>(R.id.edit)
        private val subscription = itemView.findViewById<View>(R.id.subscription)
        private val share = itemView.findViewById<View>(R.id.share)

        init {
            edit.setOnClickListener {
                item = ProfileManager.getProfile(item.id)!!
                startConfig(item)
            }
            subscription.setOnClickListener {
                item = ProfileManager.getProfile(item.id) ?: return@setOnClickListener
                startConfig(item)
            }
            TooltipCompat.setTooltipText(edit, edit.contentDescription)
            TooltipCompat.setTooltipText(subscription, subscription.contentDescription)
            itemView.setOnClickListener(this)
            share.setOnClickListener {
                val popup = PopupMenu(requireContext(), share)
                popup.menuInflater.inflate(R.menu.profile_share_popup, popup.menu)
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }
            TooltipCompat.setTooltipText(share, share.contentDescription)
        }

        fun bind(item: Profile) {
            this.item = item
            val editable = isProfileEditable(item.id)
            edit.isEnabled = editable
            edit.alpha = if (editable) 1F else .5F
            subscription.isEnabled = editable
            subscription.alpha = if (editable) 1F else .5F
            var tx = item.tx
            var rx = item.rx
            statsCache[item.id]?.apply {
                tx += txTotal
                rx += rxTotal
            }
            text1.text = item.formattedName
            val cc = item.countryCode
            if (!cc.isNullOrEmpty()) {
                countryCodeView.text = cc
                countryCodeView.visibility = View.VISIBLE
            } else {
                countryCodeView.visibility = View.GONE
            }
            text2.text = ArrayList<String>().apply {
                val id = PluginConfiguration(item.plugin ?: "").selected
                if (id.isNotEmpty()) this += getString(R.string.profile_plugin, id)
            }.joinToString("\n")
            val context = requireContext()
            traffic.text = if (tx <= 0 && rx <= 0) null else getString(R.string.traffic,
                    Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))

            if (item.id == DataStore.profileId) {
                itemView.isSelected = true
                selectedItem = this
            } else {
                itemView.isSelected = false
                if (selectedItem === this) selectedItem = null
            }

            edit.visibility = View.GONE
            subscription.visibility = View.GONE
            share.visibility = View.GONE
        }

        override fun onClick(v: View?) {
            if (isEnabled) {
                val activity = activity as MainActivity
                val old = DataStore.profileId
                Core.switchProfile(item.id)
                profilesAdapter.refreshId(old)
                itemView.isSelected = true
                if (activity.state.canStop) Core.reloadService()
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_qr_code -> {
                QRCodeDialog(this.item.toString()).showAllowingStateLoss(parentFragmentManager)
                true
            }
            R.id.action_export_clipboard -> {
                val success = Core.trySetPrimaryClip(this.item.toString(), true)
                (activity as MainActivity).snackbar().setText(
                        if (success) R.string.action_export_msg else R.string.action_export_err).show()
                true
            }
            else -> false
        }
    }

    inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>(), ProfileManager.Listener {
        internal val profiles = ProfileManager.getActiveProfiles()?.toMutableList() ?: mutableListOf()
        private val updated = HashSet<Profile>()

        init {
            setHasStableIds(true)   // see: http://stackoverflow.com/a/32488059/2245107
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.bind(profiles[position])
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder = ProfileViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false))

        override fun getItemCount(): Int = profiles.size
        override fun getItemId(position: Int): Long = profiles[position].id

        override fun onAdd(profile: Profile) {
            undoManager.flush()
            val pos = itemCount
            profiles += profile
            notifyItemInserted(pos)
        }

        fun filter(name: String) {
            val active = ProfileManager.getActiveProfiles()?.toMutableList() ?: mutableListOf()
            profiles.clear()
            val locale = resources.configuration.locale
            val lower = name.lowercase(locale)
            profiles.addAll(active.filter {
                it.name?.lowercase(locale)?.contains(lower) == true || it.host.lowercase(locale).contains(lower)
            })
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            undoManager.flush()
            val first = profiles[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(-1, from downTo to + 1)
            for (i in range) {
                val next = profiles[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                profiles[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            profiles[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            updated.forEach { ProfileManager.updateProfile(it) }
            updated.clear()
        }

        fun remove(pos: Int) {
            profiles.removeAt(pos)
            notifyItemRemoved(pos)
        }

        fun undo(actions: List<Pair<Int, Profile>>) {
            for ((index, item) in actions) {
                profiles.add(index, item)
                notifyItemInserted(index)
            }
        }

        fun commit(actions: List<Pair<Int, Profile>>) {
            for ((_, item) in actions) ProfileManager.delProfile(item.id)
        }

        fun refreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun deepRefreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return
            profiles[index] = ProfileManager.getProfile(id)!!
            notifyItemChanged(index)
        }

        override fun onRemove(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return
            profiles.removeAt(index)
            notifyItemRemoved(index)
            if (profileId == DataStore.profileId) DataStore.profileId = 0   // switch to null profile
        }

        override fun onCleared() {
            profiles.clear()
            notifyDataSetChanged()
        }

        override fun reloadProfiles() {
            profiles.clear()
            ProfileManager.getActiveProfiles()?.let { profiles.addAll(it) }
            notifyDataSetChanged()
        }
    }

    private var selectedItem: ProfileViewHolder? = null

    val profilesAdapter by lazy { ProfilesAdapter() }
    private lateinit var profilesList: RecyclerView
    private val layoutManager by lazy { LinearLayoutManager(context, RecyclerView.VERTICAL, false) }
    private lateinit var undoManager: UndoSnackbarManager<Profile>
    private val statsCache = LongSparseArray<TrafficStats>()

    private fun startConfig(profile: Profile) {
        profile.serialize()
        startActivity(Intent(context, ProfileConfigActivity::class.java).putExtra(Action.EXTRA_PROFILE_ID, profile.id))
    }

    override fun onQueryTextChange(query: String): Boolean {
        profilesAdapter.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListHolderListener)
        toolbar.setTitle(R.string.profiles)
        toolbar.inflateMenu(R.menu.profile_manager_menu)
        toolbar.setOnMenuItemClickListener(this)
        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        searchView.setOnQueryTextListener(this)
        searchView.queryHint = getString(android.R.string.search_go)

        ProfileManager.ensureNotEmpty()
        profilesList = view.findViewById(R.id.list)
        ViewCompat.setOnApplyWindowInsetsListener(profilesList, MainListListener)
        profilesList.layoutManager = layoutManager
        profilesList.addItemDecoration(DividerItemDecoration(context, layoutManager.orientation))
        layoutManager.scrollToPosition(profilesAdapter.profiles.indexOfFirst { it.id == DataStore.profileId })
        val animator = DefaultItemAnimator()
        animator.supportsChangeAnimations = false // prevent fading-in/out when rebinding
        profilesList.itemAnimator = animator
        profilesList.adapter = profilesAdapter
        instance = this
        ProfileManager.listener = profilesAdapter
        undoManager = UndoSnackbarManager(activity as MainActivity, profilesAdapter::undo, profilesAdapter::commit)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = 0
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
        }).attachToRecyclerView(profilesList)

        ProfileManager.reloadProfiles()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                ProfileManager.reloadProfiles()
                true
            }
            else -> false
        }
    }



    fun onTrafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId != 0L) {  // ignore aggregate stats
            statsCache.put(profileId, stats)
            profilesAdapter.refreshId(profileId)
        }
    }

    fun onTrafficPersisted(profileId: Long) {
        statsCache.remove(profileId)
        profilesAdapter.deepRefreshId(profileId)
    }

    override fun onDestroyView() {
        undoManager.flush()
        super.onDestroyView()
    }

    override fun onDestroy() {
        instance = null
        ProfileManager.listener = null
        super.onDestroy()
    }
}
