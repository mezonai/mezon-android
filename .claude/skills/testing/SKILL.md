---
name: testing
description: Android testing — JUnit5, Turbine for Flows, MockK, Compose UI tests. Use when writing or reviewing tests.
---

# Android Testing

## When to Use

- Writing unit tests for ViewModels, Repositories, Stores
- Testing Compose UI
- Setting up test infrastructure
- Reviewing test coverage

## Unit Tests (JUnit5 + MockK)

```kotlin
@ExtendWith(MockKExtension::class)
class ChatViewModelTest {
    @MockK private lateinit var chatRepository: ChatRepository
    @MockK private lateinit var messageStore: MessageStore

    private lateinit var viewModel: ChatViewModel

    @BeforeEach
    fun setup() {
        viewModel = ChatViewModel(chatRepository, messageStore)
    }

    @Test
    fun `initial state is Loading`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isInstanceOf(ChatUiState.Loading::class.java)
        }
    }
}
```

## Flow Testing (Turbine)

```kotlin
@Test
fun `messages emit after load`() = runTest {
    viewModel.uiState.test {
        assertThat(awaitItem()).isEqualTo(ChatUiState.Loading)
        viewModel.loadMessages(channelId)
        val success = awaitItem() as ChatUiState.Success
        assertThat(success.messages).hasSize(5)
    }
}
```

## Compose UI Tests

```kotlin
@get:Rule val composeTestRule = createComposeRule()

@Test
fun messageBubble_displaysContent() {
    composeTestRule.setContent {
        MessageBubble(message = testMessage)
    }
    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
}
```

## Test Rules

- Every public function in ViewModel/Repository should have tests
- Use `TestDispatcher` for coroutine tests
- Use Turbine for Flow assertions
- Name tests as `function_condition_expectedResult`
