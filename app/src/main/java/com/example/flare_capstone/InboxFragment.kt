package com.example.flare_capstone

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.databinding.FragmentInboxBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class InboxFragment : Fragment() {
    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private lateinit var responseMessageAdapter: ResponseMessageAdapter
    private val responseMessageList = mutableListOf<ResponseMessage>()
    private lateinit var database: DatabaseReference

    private val stationNodes = listOf("MabiniFireStation", "LaFilipinaFireStation", "CanocotanFireStation")
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    // To store unread message count for the current user
    private var unreadMessageCount: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance().reference

        responseMessageAdapter = ResponseMessageAdapter(responseMessageList)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = responseMessageAdapter

        loadUserAndAttach()
    }

    private fun loadUserAndAttach() {
        val user = FirebaseAuth.getInstance().currentUser
        val userEmail = user?.email
        if (userEmail == null) {
            Toast.makeText(context, "User is not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        // Fetch the current user's details from Firebase
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
        detachAllListeners()

        responseMessageList.clear()
        _binding?.let { binding ->
            responseMessageAdapter.notifyDataSetChanged()
            binding.noMessagesText.visibility = View.VISIBLE
        }

        unreadMessageCount = 0  // Reset unread message count for the current user

        stationNodes.forEach { station ->
            val q: Query = database.child(station)
                .child("ResponseMessage")
                .orderByChild("contact")
                .equalTo(userContact)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val b = _binding ?: return
                    var changed = false

                    snapshot.children.forEach { node ->
                        val msg = node.getValue(ResponseMessage::class.java) ?: return@forEach
                        msg.uid = node.key ?: ""

                        // Filter only the messages that belong to the current logged-in user
                        if (msg.reporterName == userName || userName.isBlank()) {
                            val idx = responseMessageList.indexOfFirst {
                                it.incidentId == msg.incidentId && it.fireStationName == msg.fireStationName
                            }
                            if (idx == -1) {
                                responseMessageList.add(0, msg)
                                changed = true
                            } else {
                                val oldTs = responseMessageList[idx].timestamp ?: 0L
                                val newTs = msg.timestamp ?: 0L
                                if (newTs > oldTs || responseMessageList[idx].isRead != msg.isRead) {
                                    responseMessageList[idx] = msg
                                    changed = true
                                }
                            }

                            // Increase unread message count if the message is not read
                            if (!msg.isRead) unreadMessageCount++
                        }
                    }

                    if (changed) responseMessageAdapter.notifyDataSetChanged()
                    b.noMessagesText.visibility = if (responseMessageList.isEmpty()) View.VISIBLE else View.GONE

                    // Update the badge with the unread message count
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

    private fun updateInboxBadge(count: Int) {
        // Access the parent activity (DashboardActivity) and check if it's the correct type
        val activity = requireActivity() // Get the hosting activity (DashboardActivity)

        if (activity is DashboardActivity) {  // Check if it is the correct activity type
            // Access bottomNavigation from the parent activity's binding
            val badge = activity.binding.bottomNavigation.getOrCreateBadge(R.id.inboxFragment)
            badge.isVisible = count > 0
            badge.number = count
            badge.maxCharacterCount = 3
        } else {
            Log.e("InboxFragment", "Parent activity is not DashboardActivity")
        }
    }


    private fun detachAllListeners() {
        liveListeners.forEach { (q, l) -> try { q.removeEventListener(l) } catch (_: Exception) {} }
        liveListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachAllListeners()
        _binding = null
    }
}
