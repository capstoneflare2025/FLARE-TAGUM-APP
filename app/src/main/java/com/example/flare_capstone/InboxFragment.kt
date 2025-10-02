package com.example.flare_capstone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.FragmentInboxBinding
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

    // Firebase
    private lateinit var database: DatabaseReference
    private val stationNodes = listOf("MabiniFireStation", "LaFilipinaFireStation", "CanocotanFireStation")
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    // Badge
    private var unreadMessageCount: Int = 0

    // Filter state
    private enum class FilterMode { ALL, READ, UNREAD }
    private var currentFilter: FilterMode = FilterMode.ALL

    // Stories search state (typed or voice)
    private var storyFilterQuery: String = ""

    // Node maps reused for stories + intents
    private val stationNodeByDisplayName = mapOf(
        "La Filipina Fire Station" to "LaFilipinaFireStation",
        "Canocotan Fire Station"   to "CanocotanFireStation",
        "Mabini Fire Station"      to "MabiniFireStation",
        "LaFilipinaFireStation"    to "LaFilipinaFireStation",
        "CanocotanFireStation"     to "CanocotanFireStation",
        "MabiniFireStation"        to "MabiniFireStation"
    )
    private val reportNodeByStationNode = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

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
                    b.searchInput.setText(speech) // triggers filtering via text watcher
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

        // Main list
        responseMessageAdapter = ResponseMessageAdapter(visibleMessages) {
            applyFilter()
            unreadMessageCount = allMessages.count { !it.isRead }
            updateInboxBadge(unreadMessageCount)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = responseMessageAdapter

        // Stories row (horizontal + snap)
        binding.storiesRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            storiesAdapter = StoriesAdapter(mutableListOf())
            adapter = storiesAdapter
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }
        LinearSnapHelper().attachToRecyclerView(binding.storiesRecycler)
        addStoryEdges()

        // Tabs filter (main list only)
        binding.tabLayout.addOnTabSelectedListener(object: com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                currentFilter = when (tab.position) {
                    1 -> FilterMode.READ
                    2 -> FilterMode.UNREAD
                    else -> FilterMode.ALL
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        // 🔎 Inline typing -> live stories filter
        binding.searchInput.addTextChangedListener { editable ->
            applyStoriesSearchQuery(editable?.toString().orEmpty())
        }

        // Focus input when tapping the pill
        binding.searchBar.setOnClickListener {
            focusAndShowKeyboard(binding.searchInput)
        }

        // Voice + Clear
        binding.voiceBtn.setOnClickListener {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        loadUserAndAttach()
    }

    // Simple start/end padding for stories so first/last can center nicely
    private fun addStoryEdges() {
        val side = resources.getDimensionPixelSize(R.dimen.story_edge_padding) // e.g. 24dp
        binding.storiesRecycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val pos = parent.getChildAdapterPosition(view)
                val last = (parent.adapter?.itemCount ?: 0) - 1
                outRect.left = if (pos == 0) side else 0
                outRect.right = if (pos == last) side else resources.getDimensionPixelSize(R.dimen.story_gap_between) // e.g. 12dp
            }
        })
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
        // If the view is already destroyed or fragment not added, do nothing
        val b = _binding ?: return
        if (!isAdded) return

        detachAllListeners()

        allMessages.clear()
        visibleMessages.clear()
        responseMessageAdapter.notifyDataSetChanged()
        b.noMessagesText.visibility = View.VISIBLE


        // Clear stories on reset with an empty message
        storiesAdapter.setData(emptyList(), "No fire station available")


        // Clear stories on reset
        storiesAdapter.setData(emptyList())
        updateInboxBadge(0)

        stationNodes.forEach { station ->
            val q: Query = database.child(station)
                .child("ResponseMessage")
                .orderByChild("contact")
                .equalTo(userContact)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val vb = _binding ?: return  // view might be gone later; safe guard
                    var changed = false

                    snapshot.children.forEach { node ->
                        val msg = node.getValue(ResponseMessage::class.java) ?: return@forEach
                        msg.uid = node.key ?: ""

                        if (msg.reporterName == userName || userName.isBlank()) {
                            val idx = allMessages.indexOfFirst {
                                it.incidentId == msg.incidentId && it.fireStationName == msg.fireStationName
                            }
                            if (idx == -1) {
                                allMessages.add(0, msg)
                                changed = true
                            } else {
                                val oldTs = allMessages[idx].timestamp ?: 0L
                                val newTs = msg.timestamp ?: 0L
                                if (newTs > oldTs || allMessages[idx].isRead != msg.isRead) {
                                    allMessages[idx] = msg
                                    changed = true
                                }
                            }
                        }
                    }

                    if (changed) {
                        applyFilter()                  // main list
                        rebuildStoriesRow(allMessages) // stories respect current query
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

    /** Apply current filter to visible list (main list only) */
    private fun applyFilter() {
        if (_binding == null) return
        visibleMessages.clear()
        when (currentFilter) {
            FilterMode.ALL    -> visibleMessages.addAll(allMessages.sortedByDescending { it.timestamp ?: 0L })
            FilterMode.READ   -> visibleMessages.addAll(allMessages.filter { it.isRead }.sortedByDescending { it.timestamp ?: 0L })
            FilterMode.UNREAD -> visibleMessages.addAll(allMessages.filter { !it.isRead }.sortedByDescending { it.timestamp ?: 0L })
        }
        responseMessageAdapter.notifyDataSetChanged()
        _binding?.noMessagesText?.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Build unique latest-per-station for stories; then filter by storyFilterQuery */
    private fun rebuildStoriesRow(source: List<ResponseMessage>) {
        val latestPerStation = source
            .filter { !it.fireStationName.isNullOrBlank() }
            .groupBy { it.fireStationName!! }
            .mapNotNull { (_, msgs) -> msgs.maxByOrNull { it.timestamp ?: 0L } }

        val q = storyFilterQuery.trim().lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) {
            latestPerStation
        } else {
            latestPerStation.filter { msg ->
                val display = msg.fireStationName?.lowercase(Locale.ROOT) ?: ""
                val node = stationNodeByDisplayName[msg.fireStationName ?: ""]?.lowercase(Locale.ROOT) ?: ""
                display.contains(q) || node.contains(q)
            }
        }

        val storyItems = filtered.map { msg ->
            val displayName = msg.fireStationName ?: "Unknown Fire Station"
            val stationNode = stationNodeByDisplayName[displayName] ?: "MabiniFireStation"
            val reportNode  = reportNodeByStationNode[stationNode] ?: "MabiniFireReport"
            StoryItem(
                fireStationName = displayName,
                incidentId      = msg.incidentId,
                uid             = msg.uid,
                contact         = msg.contact,
                reporterName    = msg.reporterName,
                stationNode     = stationNode,
                reportNode      = reportNode
            )
        }
        val emptyMsg = if (q.isEmpty()) "No fire station available" else "No matching stations"
        storiesAdapter.setData(storyItems, emptyMsg)

    }

    // Live stories search helpers
    private fun applyStoriesSearchQuery(query: String) {
        storyFilterQuery = query
        rebuildStoriesRow(allMessages)
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
