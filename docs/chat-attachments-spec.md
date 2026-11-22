# Chat Attachment Specification (iOS Reference)

This document outlines the implementation details for chat attachments, based on the iOS `bighelp` codebase.

## 1. Attachment Data Models & Limits

### `ChatAttachment` (Model)
- **Properties**:
  - `id`: Unique identifier (16-128 ASCII characters).
  - `fileName`: Name of the file (1-180 characters, no control characters).
  - `mimeType`: MIME type string (e.g., `image/jpeg`, `application/pdf`).
  - `data`: The raw byte data of the attachment.
  - `kind`: `.image` (if `mimeType` starts with `image/`) or `.file`.
- **Size Limits**:
  - **User Uploads**: Max 8 MiB per attachment (`ChatAttachment.maximumBytes`).
  - **Agent Artifacts**: Max 25 MiB per attachment (`ChatAttachment.maximumAgentBytes`).
  - **Batch Limit**: Total size of all attachments in a single message must not exceed 24 MiB.
  - **Count Limit**: Maximum of 10 attachments per message.

### Errors
- `ChatAttachmentError`: Covers validation failures like `invalidID`, `invalidFileName`, `invalidMIMEType`, `invalidSize`, `unsupportedKind`, `unsupportedClient`, and `tooMany`.
- `DirectHermesError`: Handles connection and session-related issues during upload.

---

## 2. UI Layout & Interactions

### Draft Rail (`DraftAttachmentRail`)
A small tag displayed above the message input field.
- **States**:
  - **Adding**: Shows a circular progress indicator (`progress` 0...1).
  - **Failure**: Displays a "!" icon and a "Try again" button if any attachments failed to attach.
  - **Normal**: Displays a series of thumbnails. Photos lead the stack (up to 3), followed by files.
- **Interaction**: Tapping the rail opens the `DraftAttachmentsSheet`.

### Attachment Sheet (`DraftAttachmentsSheet`)
A full-screen or medium-sized sheet for managing current drafts.
- **Content**:
  - **Progress**: A progress view is shown while attachments are being imported.
  - **Failures**: A "Didn't attach" section lists failed items with a "Try again" button.
  - **List**: A list of all pending attachments. Each row shows a thumbnail (image or file icon), name, and size/page count.
- **Interactions**:
  - **Preview**: Tapping a row opens `ChatAttachmentPreviewView`.
  - **Remove**: Users can tap a "Remove" button on a row or use swipe actions to delete an attachment from the draft.
  - **Remove All**: A "Remove all" option is available in the toolbar if multiple attachments exist.

### Message Gallery (`ChatAttachmentGallery`)
Displays attachments within a sent message.
- **Layout**: Uses `ViewThatFits` to switch between a single row and a horizontal scroll view.
- **Visuals**:
  - **Images**: Shown as full-size tiles (or part of a stack if multiple images are present).
  - **Files**: Shown as a `doc.fill` icon with the filename and size.
- **Interaction**: Tapping an attachment opens its preview.

---

## 3. Upload & Handling Logic

### Upload Workflows
- **Standard Attachments**:
  - **Images**: Uploaded via `image.attach_bytes` RPC method.
  - **Files**: Uploaded via `file.attach` RPC method.
- **PDF Page Selections**:
  - Staged using `file.attach` first.
  - Finalized using `pdf.attach` with the specific page range and target information.
- **Large Files (> 8 MB)**:
  - Handled via a specialized `BigFileUploader` plugin.
  - Once staged on the host, the client notifies Hermes of the file path via `file.attach`.

### Preview & Exporting
- **`ChatAttachmentPreviewView`**:
  - **Markdown**: Supports rendering `.md` or `.markdown` files (up to 1MB) in either 'Preview' (rendered) or 'Raw' (text) mode.
  - **Media**: Provides a video player for video files.
  - **QuickLook**: Uses system `QLPreviewController` for other file types.
  - **Actions**:
    - **Save to Photos**: For images and videos.
    - **Save to Files**: For all attachment types.
    - **Share**: Standard system share sheet.

### Error Recovery
- **Retries**: Failed attachments can be retried via the `DraftAttachmentsSheet` or the `DraftAttachmentRail`.
- **Stale State**: PDF page selections are validated against the current `PDFAttachmentTarget` to ensure they are still valid before submission.
```