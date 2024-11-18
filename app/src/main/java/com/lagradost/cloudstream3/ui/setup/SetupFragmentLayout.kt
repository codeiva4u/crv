package com.lagradost.cloudstream3.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentSetupLayoutBinding
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbar
import org.acra.ACRA

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
        //return inflater.inflate(R.layout.fragment_setup_layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fixPaddingStatusbar(binding?.setupRoot)

        normalSafeApiCall {
            val ctx = context ?: return@normalSafeApiCall

            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

            // डिफ़ॉल्ट रूप से "Auto" सेट करना
            val defaultLayoutValue = resources.getIntArray(R.array.app_layout_values)[0] // Assuming "Auto" is the first value
            settingsManager.edit()
                .putInt(getString(R.string.app_layout_key), defaultLayoutValue)
                .apply()

            // सेटअप को समाप्त करना और होम स्क्रीन पर नेविगेट करना
            setKey(HAS_DONE_SETUP_KEY, true)
            findNavController().navigate(R.id.navigation_home)
        }
    }
}