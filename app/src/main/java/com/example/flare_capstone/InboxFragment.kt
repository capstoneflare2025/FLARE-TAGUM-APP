package com.example.flare_capstone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.FragmentInboxBinding
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.Locale

class InboxFragment : Fragment() {
    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    // Adapters
    private lateinit var responseMessageAdapter: ResponseMessageAdapter
    private lateinit var storiesAdapter: StoriesAdapter

    // Master + visible (filtered) lists
    private val allMessages = mutableListOf<ResponseMessage>()
    private val visibleMessages = mutableListOf<ResponseMessage>()

    private enum class CategoryFilter { ALL, FIRE, OTHER }

    // Default to FIRE category + All Fire Station selected
    private var currentCategoryFilter: CategoryFilter = CategoryFilter.FIRE
    private var selectedStation: String = "All Fire Station"

    // Firebase
    private lateinit var database: DatabaseReference
    private val stationNodes = listOf("MabiniFireStation", "LaFilipinaFireStation", "CanocotanFireStation")
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    // Badge
    private var unreadMessageCount: Int = 0

    // Read/Unread tabs
    private enum class FilterMode { ALL, READ, UNREAD }
    private var currentFilter: FilterMode = FilterMode.ALL

    // Voice search
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition()
        else Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val speech = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (speech.isNotEmpty()) {
                _binding?.let { b ->
                    b.searchInput.setText(speech)
                    b.searchInput.setSelection(b.searchInput.text?.length ?: 0)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance().reference

        // Main list (messages)
        responseMessageAdapter = ResponseMessageAdapter(visibleMessages) {
            applyFilter()
            unreadMessageCount = allMessages.count { !it.isRead }
            updateInboxBadge(unreadMessageCount)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = responseMessageAdapter

        // Stories row
        binding.storiesRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            storiesAdapter = StoriesAdapter(mutableListOf()) { station ->
                selectedStation = station
                applyFilter()
            }
            adapter = storiesAdapter
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }
        LinearSnapHelper().attachToRecyclerView(binding.storiesRecycler)
        addStoryEdges()

        // Default stations
        storiesAdapter.setDefaultStations()

        // Read/Unread tabs
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = when (tab.position) {
                    1 -> FilterMode.READ
                    2 -> FilterMode.UNREAD
                    else -> FilterMode.ALL
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Default category = FIRE
        binding.categoryChips.check(binding.chipFire.id)
        currentCategoryFilter = CategoryFilter.FIRE
        updateStoriesRow("")
        applyFilter()

        // Chip listeners
        binding.categoryChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategoryFilter = when {
                checkedIds.contains(binding.chipFire.id)  -> CategoryFilter.FIRE
                checkedIds.contains(binding.chipOther.id) -> CategoryFilter.OTHER
                else -> CategoryFilter.ALL
            }
            updateStoriesRow(binding.searchInput.text?.toString().orEmpty())
            applyFilter()
        }

        // Search bar typing
        binding.searchInput.addTextChangedListener { editable ->
            updateStoriesRow(editable?.toString().orEmpty())
        }

        binding.searchBar.setOnClickListener {
            focusAndShowKeyboard(binding.searchInput)
        }

        binding.voiceBtn.setOnClickListener {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        loadUserAndAttach()
    }

    private fun addStoryEdges() {
        val side = resources.getDimensionPixelSize(R.dimen.story_edge_padding)
        binding.storiesRecycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val pos = parent.getChildAdapterPosition(view)
                val last = (parent.adapter?.itemCount ?: 0) - 1
                outRect.left = if (pos == 0) side else 0
                outRect.right = if (pos == last) side else resources.getDimensionPixelSize(R.dimen.story_gap_between)
            }
        })
    }

    private fun updateStoriesRow(query: String) {
        val stations = listOf(
            "All Fire Station",
            "LaFilipina Fire Station",
            "Canocotan Fire Station",
            "Mabini Fire Station"
        )

        val filtered = if (query.isBlank()) stations
        else stations.filter { it.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT)) }

        val items = filtered.map { StoryItem(it) }
        storiesAdapter.setData(items, if (items.isEmpty()) "No matching stations" else null)
    }

    private fun loadUserAndAttach() {
        val userEmail = FirebaseAuth.getInstance().currentUser?.email
        if (userEmail == null) {
            Toast.makeText(context, "User is not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("Users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        Toast.makeText(context, "User not found.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val userSnap = snap.children.first()
                    val userName = userSnap.child("name").getValue(String::class.java) ?: ""
                    val userContact = userSnap.child("contact").getValue(String::class.java) ?: ""
                    attachStationInboxListeners(userName, userContact)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("Inbox", "User lookup cancelled: ${error.message}")
                }
            })
    }

    private fun attachStationInboxListeners(userName: String, userContact: String) {
        val b = _binding ?: return
        if (!isAdded) return

        detachAllListeners()
        allMessages.clear()
        visibleMessages.clear()
        responseMessageAdapter.notifyDataSetChanged()
        b.noMessagesText.visibility = View.VISIBLE

        updateInboxBadge(0)

        stationNodes.forEach { station ->
            val q: Query = database.child(station)
                .child("ResponseMessage")
                .orderByChild("contact")
                .equalTo(userContact)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val vb = _binding ?: return
                    var changed = false

                    snapshot.children.forEach { node ->
                        val msg = node.getValue(ResponseMessage::class.java) ?: return@forEach
                        msg.uid = node.key ?: ""
                        msg.stationNode = station

                        if (msg.reporterName == userName || userName.isBlank()) {
                            val idx = allMessages.indexOfFirst {
                                it.incidentId == msg.incidentId && it.fireStationName == msg.fireStationName
                            }
                            if (idx == -1) {
                                allMessages.add(0, msg)
                                changed = true
                                resolveCategoryForMessage(station, msg)
                            } else {
                                val oldTs = allMessages[idx].timestamp ?: 0L
                                val newTs = msg.timestamp ?: 0L
                                if (newTs > oldTs || allMessages[idx].isRead != msg.isRead) {
                                    allMessages[idx] = msg
                                    changed = true
                                    resolveCategoryForMessage(station, msg)
                                }
                            }
                        }
                    }

                    if (changed) {
                        applyFilter()
                    }

                    vb.noMessagesText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
                    unreadMessageCount = allMessages.count { !it.isRead }
                    updateInboxBadge(unreadMessageCount)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Inbox", "Listener cancelled: ${error.message}")
                }
            }

            q.addValueEventListener(listener)
            liveListeners.add(q to listener)
        }
    }

    private fun resolveCategoryForMessage(stationNode: String, msg: ResponseMessage) {
        val threadId = msg.incidentId ?: msg.uid ?: return

        val fireNode = when (stationNode) {
            "LaFilipinaFireStation" -> "LaFilipinaFireReport"
            "CanocotanFireStation"  -> "CanocotanFireReport"
            "MabiniFireStation"     -> "MabiniFireReport"
            else -> null
        }
        val otherNode = when (stationNode) {
            "LaFilipinaFireStation" -> "LaFilipinaOtherEmergency"
            "CanocotanFireStation"  -> "CanocotanOtherEmergency"
            "MabiniFireStation"     -> "MabiniOtherEmergency"
            else -> null
        }

        if (fireNode != null) {
            database.child(stationNode).child(fireNode).child(threadId).child("messages")
                .limitToFirst(1)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        if (snap.exists() && snap.childrenCount > 0) {
                            msg.category = "fire"
                        } else if (otherNode != null) {
                            database.child(stationNode).child(otherNode).child(threadId).child("messages")
                                .limitToFirst(1)
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(s2: DataSnapshot) {
                                        msg.category = if (s2.exists() && s2.childrenCount > 0) "other" else "unknown"
                                        applyFilter()
                                    }
                                    override fun onCancelled(error: DatabaseError) {
                                        msg.category = "unknown"
                                        applyFilter()
                                    }
                                })
                        } else {
                            msg.category = "unknown"
                        }
                        applyFilter()
                    }
                    override fun onCancelled(error: DatabaseError) {
                        msg.category = "unknown"
                        applyFilter()
                    }
                })
        }
    }

    private fun applyFilter() {
        if (_binding == null) return
        visibleMessages.clear()

        val base = when (currentFilter) {
            FilterMode.ALL    -> allMessages
            FilterMode.READ   -> allMessages.filter { it.isRead }
            FilterMode.UNREAD -> allMessages.filter { !it.isRead }
        }

        val filteredByCategory = base.filter { msg ->
            when (currentCategoryFilter) {
                CategoryFilter.ALL   -> true
                CategoryFilter.FIRE  -> msg.category == "fire"
                CategoryFilter.OTHER -> msg.category == "other"
            }
        }

        val filteredByStation = filteredByCategory.filter { msg ->
            if (selectedStation == "All Fire Station") true
            else msg.fireStationName == selectedStation
        }

        visibleMessages.addAll(filteredByStation.sortedByDescending { it.timestamp ?: 0L })
        responseMessageAdapter.notifyDataSetChanged()
        _binding?.noMessagesText?.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a fire station name")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Voice input not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusAndShowKeyboard(view: View) {
        view.requestFocus()
        view.post {
            if (!isAdded) return@post
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateInboxBadge(count: Int) {
        if (!isAdded) return
        (activity as? DashboardActivity)?.let { act ->
            val badge = act.binding.bottomNavigation.getOrCreateBadge(R.id.inboxFragment)
            badge.isVisible = count > 0
            badge.number = count
            badge.maxCharacterCount = 3
        } ?: Log.e("InboxFragment", "Parent activity is not DashboardActivity")
    }

    private fun detachAllListeners() {
        liveListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        liveListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachAllListeners()
        _binding = null
    }
}
