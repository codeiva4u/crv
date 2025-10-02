package com.lagradost.cloudstream3.ui.setup
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentSetupLayoutBinding
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbar

class SetupFragmentLayout : Fragment() {
    var binding: FragmentSetupLayoutBinding? = null
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val localBinding = FragmentSetupLayoutBinding.inflate(inflater, container, false)
        binding = localBinding
        return localBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fixPaddingStatusbar(binding?.setupRoot)
        try {
            val ctx = context ?: return@onViewCreated
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

 //========================= नया बदलाव: "Auto" को डिफ़ॉल्ट रूप से सेट करें =====================
            val defaultLayoutValue = resources.getIntArray(R.array.app_layout_values)[0] // Assuming "Auto" is the first value
            settingsManager.edit()
                .putInt(getString(R.string.app_layout_key), defaultLayoutValue)
                .apply()

            binding?.apply {

                // **अगले बटन पर क्लिक करने पर: सीधे होम स्क्रीन पर नेविगेट करें**
                nextBtt.setOnClickListener {
                    setKey(HAS_DONE_SETUP_KEY, true)
                    findNavController().navigate(R.id.navigation_home)
                }

                // **पिछले बटन पर क्लिक करने पर: पिछले स्क्रीन पर वापस जाएँ**
                prevBtt.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
//===========================================================================================
