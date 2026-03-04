package ai.mezon.app.home.profile

import ai.mezon.app.R
import ai.mezon.app.auth.AuthRepository
import ai.mezon.app.session.LocaleManager
import ai.mezon.app.session.SessionManager
import ai.mezon.app.session.ThemeManager
import ai.mezon.app.ui.theme.Dimens
import ai.mezon.app.ui.theme.ThemeMode
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val localeManager: LocaleManager,
    private val themeManager: ThemeManager,
    sessionManager: SessionManager
) : ViewModel() {

    val userId = sessionManager.sessionFlow
        .map { it?.userId ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val currentLanguage = localeManager.currentLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocaleManager.ENGLISH)

    val currentTheme = themeManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { localeManager.setLanguage(tag) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { themeManager.setTheme(mode) }
    }
}

@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val userId by viewModel.userId.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingLarge)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingSmall)
        ) {
            Spacer(Modifier.height(Dimens.paddingLarge))

            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineMedium
            )

            if (userId.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.profile_user_id, userId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(Dimens.paddingSmall))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Dimens.paddingSmall))

            SectionHeader(icon = Icons.Default.Palette, title = stringResource(R.string.setting_theme_title))

            ThemeOption(
                labelRes = R.string.setting_theme_light,
                isSelected = currentTheme == ThemeMode.LIGHT,
                onClick = { viewModel.setTheme(ThemeMode.LIGHT) }
            )
            ThemeOption(
                labelRes = R.string.setting_theme_dark,
                isSelected = currentTheme == ThemeMode.DARK,
                onClick = { viewModel.setTheme(ThemeMode.DARK) }
            )
            ThemeOption(
                labelRes = R.string.setting_theme_abyss,
                isSelected = currentTheme == ThemeMode.ABYSS,
                onClick = { viewModel.setTheme(ThemeMode.ABYSS) }
            )
            ThemeOption(
                labelRes = R.string.setting_theme_system,
                isSelected = currentTheme == ThemeMode.SYSTEM,
                onClick = { viewModel.setTheme(ThemeMode.SYSTEM) }
            )

            Spacer(Modifier.height(Dimens.paddingSmall))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Dimens.paddingSmall))

            SectionHeader(icon = Icons.Default.Language, title = stringResource(R.string.setting_language_title))

            SettingOption(
                label = stringResource(R.string.setting_language_english),
                isSelected = currentLanguage == LocaleManager.ENGLISH,
                onClick = { viewModel.setLanguage(LocaleManager.ENGLISH) }
            )
            SettingOption(
                label = stringResource(R.string.setting_language_vietnamese),
                isSelected = currentLanguage == LocaleManager.VIETNAMESE,
                onClick = { viewModel.setLanguage(LocaleManager.VIETNAMESE) }
            )

            Spacer(Modifier.height(Dimens.paddingSmall))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Dimens.paddingDefault))

            Button(
                onClick = { viewModel.logout(onLogout) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.profile_sign_out))
            }

            Spacer(Modifier.height(Dimens.paddingLarge))
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.paddingMedium))
        Text(text = title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ThemeOption(@StringRes labelRes: Int, isSelected: Boolean, onClick: () -> Unit) {
    SettingOption(
        label = stringResource(labelRes),
        isSelected = isSelected,
        onClick = onClick
    )
}

@Composable
private fun SettingOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.paddingMedium, horizontal = Dimens.paddingDefault),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
