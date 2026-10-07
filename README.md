# Mac Local LLM Chat

A lightweight Java Swing chat interface for the Apple Foundation Models command-line tool included with macOS 27.

The project provides a familiar desktop chat UI for Apple's on-device language model, including streamed responses, persistent conversation history, conversation switching, and cancellation. It uses only the Java standard library and the system-provided `fm` command.

## Features

- Chat-style user and assistant message bubbles
- Streaming model responses
- Persistent conversation history in a left sidebar
- Resume any saved conversation with its original model context
- Start a new conversation without creating empty history entries
- Delete an individual conversation after confirmation
- Stop an in-progress generation
- Automatic removal of ANSI terminal codes and `fm` transcript notices
- Native system look and feel through Java Swing
- No third-party Java dependencies

## Requirements

- macOS 27 or later
- An Apple Silicon Mac that supports Apple Intelligence
- Apple Intelligence enabled and the system language model available
- Java 21 or later

The prototype has been compiled with Java 24.

Verify model availability from Terminal:

```sh
fm available
```

The first use of `fm` may require accepting Apple's legal notice:

```sh
fm license
```

## Running the application

### From Finder

Double-click `Mac Local LLM Chat.app`.

If macOS blocks the first launch, Control-click the application, choose **Open**, and confirm the prompt.

The application bundle still requires a compatible Java installation. It does not embed its own Java runtime.

### From source

Run the included launcher script:

```sh
./run.command
```

The script compiles `src/LocalLLMChat.java` into `build/` and starts the application.

To compile and run manually:

```sh
mkdir -p build
javac -encoding UTF-8 -d build src/LocalLLMChat.java
java -cp build LocalLLMChat
```

## Controls

- **Return**: send the current message
- **Shift + Return**: insert a line break
- **Stop**: terminate the current model process
- **New conversation**: clear the conversation pane and create an unsaved draft
- **History item**: open and continue a saved conversation
- **Delete selected history**: permanently delete the selected conversation

An empty draft is not added to the history sidebar. It becomes a saved conversation only after its first message is sent.

## How it works

For each user message, the application launches:

```sh
/usr/bin/fm respond --stream ...
```

Java reads standard output incrementally on a virtual thread. Each received text chunk is forwarded to Swing's Event Dispatch Thread and appended to the active assistant bubble.

On the first turn, the application supplies model instructions and saves an `fm` transcript. Later turns use `--resume`, because the transcript already contains those instructions. Supplying both `--resume` and `--instructions` would cause `fm` to reject the request.

## Stored data

Conversation data is stored locally under:

```text
~/Library/Application Support/Mac Local LLM Chat/chats/
```

Each conversation has its own UUID-named directory containing:

- `metadata.properties` — display title and last-updated time
- `messages.tsv` — UI message history, with message bodies Base64-encoded
- `fm-transcript.json` — model context created and consumed by `fm`

Deleting a conversation from the sidebar deletes that conversation directory. This operation cannot be undone.

## Project structure

```text
LocalLLMChat/
├── src/
│   └── LocalLLMChat.java
├── Mac Local LLM Chat.app/
├── run.command
└── README.md
```

## Privacy

This application invokes the system model exposed by Apple's `fm` tool and does not implement its own network communication. Conversation history is written to the local path shown above. Model availability and processing behavior remain subject to the macOS and Apple Intelligence configuration on the user's Mac.

## Current limitations

- The interface text and default model instruction are currently in Japanese.
- Markdown is displayed as plain text.
- There is no search, export, or rename function for conversations.
- The application bundle depends on a separately installed Java runtime.
- This is a prototype and has not been prepared for Mac App Store distribution.

## License

No open-source license is included yet. Add a `LICENSE` file before publishing if you want others to copy, modify, or redistribute the code.
