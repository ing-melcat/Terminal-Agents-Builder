package com.melcat.localfirst.feature.agents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.melcat.localfirst.feature.agents.presentation.AgentDraftState
import com.melcat.localfirst.feature.agents.presentation.AgentsUiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorSheet(
    draft: AgentDraftState,
    errorMessage: String?,
    onEvent: (AgentsUiEvent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = { onEvent(AgentsUiEvent.DismissEditor) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (draft.id == null) "New Terminal Agent" else "Edit Terminal Agent",
                style = MaterialTheme.typography.headlineMedium,
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onEvent(AgentsUiEvent.DraftNameChanged(it)) },
                label = { Text("Agent Name (e.g. agy-auditor)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.role,
                onValueChange = { onEvent(AgentsUiEvent.DraftRoleChanged(it)) },
                label = { Text("Role / Specialty") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.command,
                onValueChange = { onEvent(AgentsUiEvent.DraftCommandChanged(it)) },
                label = { Text("CLI Command (e.g. agy --skill senior-auditor)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.skillsInput,
                onValueChange = { onEvent(AgentsUiEvent.DraftSkillsChanged(it)) },
                label = { Text("Active Skills (comma-separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.systemPrompt,
                onValueChange = { onEvent(AgentsUiEvent.DraftSystemPromptChanged(it)) },
                label = { Text("System Prompt / Execution Rules") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onEvent(AgentsUiEvent.DismissEditor) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEvent(AgentsUiEvent.SaveDraftAgent)
                    },
                    enabled = draft.isValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save to Local DB")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
