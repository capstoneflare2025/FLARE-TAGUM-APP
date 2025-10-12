package com.example.flare_capstone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class InboxFireFighterFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FireFighterResponseMessageAdapter
    private var emptyText: TextView? = null
    private var searchInput: EditText? = null

    // DB
    private var accountsRef: Query? = null
    private var accountsListener: ValueEventListener? = null
    private val lastMsgListeners = mutableMapOf<String, Pair<Query, ValueEventListener>>()

    // Keep items by id so we can update lastMessage live
    private val stationMap = linkedMapOf<String, FireFighterStation>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_inbox_fire_fighter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        emptyText = view.findViewById(R.id.noMessagesText)
        searchInput = view.findViewById(R.id.searchInput)

        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }

        adapter = FireFighterResponseMessageAdapter(mutableListOf()) { station ->
            // TODO: open chat screen for this station.id if needed
        }
        recyclerView.adapter = adapter

        attachForLoggedInAccount()
    }

    private fun attachForLoggedInAccount() {
        val email = FirebaseAuth.getInstance().currentUser?.email?.lowercase()
        if (email.isNullOrBlank()) {
            showEmpty("Not signed in.")
            return
        }

        // /TagumCityCentralFireStation/FireFighter/AllFireFighterAccount
        val root = FirebaseDatabase.getInstance()
            .getReference("TagumCityCentralFireStation")
            .child("FireFighter")
            .child("AllFireFighterAccount")

        // Match the account by email
        accountsRef = root.orderByChild("email").equalTo(email)
        accountsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                clearLastMsgListeners()
                stationMap.clear()

                if (!snapshot.hasChildren()) {
                    pushListUpdate()
                    showEmpty("No account found for $email")
                    return
                }

                snapshot.children.forEach { accSnap ->
                    val id = accSnap.key ?: return@forEach
                    val name = accSnap.child("name").getValue(String::class.java) ?: id
                    val profileUrl = accSnap.child("profileUrl").getValue(String::class.java) ?: ""

                    // Base item; last message info filled by listener below
                    stationMap[id] = FireFighterStation(
                        id = id,
                        name = name,
                        lastMessage = "",
                        timestamp = 0L,
                        profileUrl = profileUrl,
                        lastSender = "" // will be filled
                    )

                    // Listen for latest AdminMessages
                    val lastMsgQuery = accSnap.ref.child("AdminMessages")
                        .orderByChild("timestamp")
                        .limitToLast(1)

                    val msgListener = object : ValueEventListener {
                        override fun onDataChange(msgSnap: DataSnapshot) {
                            var lastText = ""
                            var lastTs = 0L
                            var lastSender = ""

                            for (m in msgSnap.children) {
                                lastText = m.child("text").getValue(String::class.java) ?: ""
                                lastTs = m.child("timestamp").getValue(Long::class.java) ?: 0L
                                lastSender = m.child("sender").getValue(String::class.java) ?: ""
                            }

                            stationMap[id]?.let { prev ->
                                stationMap[id] = prev.copy(
                                    lastMessage = lastText,
                                    timestamp = lastTs,
                                    lastSender = lastSender
                                )
                                pushListUpdate()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) { /* ignore */ }
                    }

                    lastMsgQuery.addValueEventListener(msgListener)
                    lastMsgListeners[id] = lastMsgQuery to msgListener
                }

                // Initial draw
                pushListUpdate()
            }

            override fun onCancelled(error: DatabaseError) {
                showEmpty("Failed to load account.")
            }
        }

        accountsRef?.addValueEventListener(accountsListener as ValueEventListener)
    }

    private fun pushListUpdate() {
        val list = stationMap.values.sortedByDescending { it.timestamp }
        adapter.updateData(list)
        emptyText?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) emptyText?.text = "No messages available"
    }

    private fun showEmpty(msg: String) {
        adapter.updateData(emptyList())
        emptyText?.visibility = View.VISIBLE
        emptyText?.text = msg
    }

    private fun clearLastMsgListeners() {
        lastMsgListeners.forEach { (_, pair) ->
            pair.first.removeEventListener(pair.second)
        }
        lastMsgListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        accountsListener?.let { l -> accountsRef?.removeEventListener(l) }
        accountsListener = null
        accountsRef = null
        clearLastMsgListeners()
        recyclerView.adapter = null
    }
}
