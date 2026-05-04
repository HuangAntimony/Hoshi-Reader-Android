# Navigation And Reader Entry Characterization

This checklist records the durable emulator-only baseline for navigation and reader entry behavior.

## Top-level tabs

- Books, Dictionary, and Settings remain top-level destinations.
- Switching top-level tabs preserves the explicit Navigation3 back stack behavior.

## Settings detail return

- Opening a Settings detail route and returning goes back to Settings without changing top-level tab state.

## Reader open and close

- Opening a bookshelf item enters Reader for the selected book.
- Closing Reader returns to the previous bookshelf state.

## Android Back from reader

- Android Back exits Reader through the same close path as the Reader chrome back action.

## External EPUB open

- An external EPUB import request is consumed once and routes to Reader after import succeeds.

## Bookmark restoration

- Reopening a book restores the saved chapter and progress.
- Bookmark saves refresh bookshelf progress after returning from Reader.

## Sasayaki media-session return

- Returning from the Sasayaki media-session notification restores the app task instead of creating a duplicate task.
