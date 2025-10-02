package com.lagradost.cloudstream3.ui.setup
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSetupMediaBinding
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbar

class SetupFragmentMedia : Fragment() {
    var binding: FragmentSetupMediaBinding? = null
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val localBinding = FragmentSetupMediaBinding.inflate(inflater, container, false)
        binding = localBinding
        return localBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            fixPaddingStatusbar(binding?.setupRoot)
            val ctx = context ?: return@onViewCreated
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

 //==================नया बदलाव: Movie, TvSeries, Live को डिफ़ॉल्ट रूप से सेट करें =================
            // डिफ़ॉल्ट मान सेट करना
            val defaultValues = setOf(
                TvType.Movie.ordinal.toString(),
                TvType.TvSeries.ordinal.toString(),
                TvType.Live.ordinal.toString()
            )
            settingsManager.edit()
                .putStringSet(getString(R.string.prefer_media_type_key), defaultValues)
                .apply()

            // UI को छुपाना
            binding?.setupRoot?.visibility = View.GONE

            // Regenerate set homepage
            DataStoreHelper.currentHomePage = null

            // अगले स्क्रीन पर जाने के लिए बटन क्लिक करें
            findNavController().navigate(R.id.navigation_setup_media_to_navigation_setup_layout)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}

//============================================================================================
