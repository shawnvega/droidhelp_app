# Settings & Storage Conventions

## DataStore Preferences
User preferences are stored using Jetpack DataStore (Preferences).
- **Definition**: Use `preferencesDataStore` extension on `Context`.
- **Reading**: Expose settings as `Flow<T>` by mapping the `data` stream.
- **Writing**: Use `suspend` functions that call `edit { ... }` on the DataStore instance.

## Secure Storage vs. Preferences
- **DataStore**: Used for non-sensitive user preferences (e.g., theme mode, analytics toggle).
- **`SecureStorage`**: Used for sensitive information (e.g., authentication tokens, private keys) that requires encryption.

## Material 3 Compose Conventions
The UI follows Material 3 design system standards:
- **Theme**: Use the provided `Theme` composable to wrap the application, which configures `MaterialTheme` with the appropriate `colorScheme` and `typography`.
- **Components**: Utilize standard Material 3 components such as `Scaffold` for screen structure and `ListItem` for list entries.

## App Storage & Cache
To monitor or manage app storage usage, use the following Android `Context` properties:
- `context.cacheDir`: Access to the application's cache directory.
- `context.filesDir`: Access to the application's internal files directory.
