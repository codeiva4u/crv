package com.lagradost.cloudstream3.ui.setup
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentSetupLanguageBinding
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.appLanguages
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbar
const val HAS_DONE_SETUP_KEY = "HAS_DONE_SETUP"
class SetupFragmentLanguage : Fragment() {
    var binding: FragmentSetupLanguageBinding? = null
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val localBinding = FragmentSetupLanguageBinding.inflate(inflater, container, false)
        binding = localBinding
        return localBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        normalSafeApiCall {
            fixPaddingStatusbar(binding?.setupRoot)
            val ctx = context ?: return@normalSafeApiCall
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
            binding?.apply {
                // Set app icon based on build type
                normalSafeApiCall {
                    val drawable = when {
                        BuildConfig.DEBUG -> R.drawable.cloud_2_gradient_debug
                        BuildConfig.BUILD_TYPE == "prerelease" -> R.drawable.cloud_2_gradient_beta
                        else -> R.drawable.cloud_2_gradient
                    }
                    appIconImage.setImageDrawable(ContextCompat.getDrawable(ctx, drawable))
                }

 //========== नया बदलाव: केवल अंग्रेजी भाषा को डिफ़ॉल्ट रूप से सेट करे ===============================

                val englishLanguage = appLanguages.find { it.third == "en" }
                val languageCodes = listOf(englishLanguage?.third ?: "en")
                val languageNames = listOf("${englishLanguage?.first ?: "🇬🇧"} ${englishLanguage?.second ?: "English"}")

                // **नया बदलाव: अन्य भाषाओं को छुपाएँ**
                listview1.visibility = View.GONE // ListView को छुपाएँ
                nextBtt.setOnClickListener {
                    // Set English as the default language
                    CommonActivity.setLocale(activity, "en")
                    settingsManager.edit().putString(getString(R.string.locale_key), "en").apply()

                    // Navigate to the next screen
                    val nextDestination = if (
                        PluginManager.getPluginsOnline().isEmpty()
                        && PluginManager.getPluginsLocal().isEmpty()
                    ) R.id.action_navigation_global_to_navigation_setup_extensions
                    else R.id.action_navigation_setup_language_to_navigation_setup_provider_languages
                    findNavController().navigate(
                        nextDestination,
                        SetupFragmentExtensions.newInstance(true)
                    )
                }

                // Skip button action
                skipBtt.setOnClickListener {
                    findNavController().navigate(R.id.navigation_home)
                }
            }
        }
    }
}