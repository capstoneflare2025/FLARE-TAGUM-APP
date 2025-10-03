package com.example.flare_capstone

import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.MediaController
import androidx.annotation.RawRes
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import com.example.flare_capstone.databinding.FragmentVideoDialogBinding

class VideoDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_RES_ID = "arg_res_id"

        fun newInstance(title: String, @RawRes resId: Int) =
            VideoDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putInt(ARG_RES_ID, resId)
                }
            }
    }

    private var _binding: FragmentVideoDialogBinding? = null
    private val binding get() = _binding!!

    private var resId: Int = 0
    private var titleText: String = "Tutorial"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_Flare_VideoDialog)
        arguments?.let {
            titleText = it.getString(ARG_TITLE, "Tutorial")
            resId = it.getInt(ARG_RES_ID, 0)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.topBar.title = titleText
        binding.topBar.setNavigationOnClickListener { dismiss() }
        if (resId == 0) { dismiss(); return }

        val uri = Uri.parse("android.resource://${requireContext().packageName}/$resId")

        // Anchor controls to the container, not the VideoView
        val controller = MediaController(requireContext()).apply {
            setAnchorView(binding.videoContainer)
        }

        // Show spinner until ready
        binding.progress.visibility = View.VISIBLE

        binding.videoView.apply {
            setMediaController(controller)
            setVideoURI(uri)
            setOnPreparedListener { mp ->
                binding.progress.visibility = View.GONE
                mp.isLooping = false

                // --- Preserve aspect ratio inside the container ---
                val vw = mp.videoWidth.coerceAtLeast(1)
                val vh = mp.videoHeight.coerceAtLeast(1)

                // Available size inside the card (container already excludes toolbar)
                binding.videoContainer.post {
                    val availW = binding.videoContainer.width
                    val availH = binding.videoContainer.height

                    val scale = minOf(availW.toFloat() / vw, availH.toFloat() / vh)
                    val targetW = (vw * scale).toInt()
                    val targetH = (vh * scale).toInt()

                    // Center by layout_gravity + exact size
                    binding.videoView.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = targetW
                        height = targetH
                        gravity = Gravity.CENTER
                    }
                    requestLayout()
                    start()
                }
            }

            setOnErrorListener { _, what, extra ->
                binding.progress.visibility = View.GONE
                // leave the black container visible; controls still work
                true
            }
        }
    }

    // Let the dialog wrap to the 300x500 card
    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onPause() {
        super.onPause()
        _binding?.videoView?.let { if (it.isPlaying) it.pause() }
    }

    override fun onDestroyView() {
        _binding?.videoView?.stopPlayback()
        _binding = null
        super.onDestroyView()
    }
}
