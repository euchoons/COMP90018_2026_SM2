# Implementation Plan - Authentication Integration

Integrate the existing Firebase Authentication flow into the main application navigation. This will ensure users are prompted to sign in or register before using the app's core features, while also providing a way to manage their account (Sign Out) once authenticated.

## User Review Required

> [!IMPORTANT]
> This change will force an authentication gate at startup. Users will need to either Sign In, Register, or select "Continue as Anonymous Guest" to proceed to the Home screen.

- **Navigation Change**: A new "Account" tab will be added to the bottom navigation bar to allow users to view their profile and Sign Out.
- **Anonymous Access**: The "Continue as Anonymous Guest" option will still be available for users who don't want to create a full account immediately.

## Proposed Changes

### Core Models

#### [MODIFY] [Models.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/domain/model/Models.kt)
- Add `ACCOUNT` to the `AppScreen` enum.

---

### UI Layer

#### [MODIFY] [FloraGuideViewModel.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/ui/FloraGuideViewModel.kt)
- Add `goToAccount()` function to navigate to the account screen.
- Ensure `AuthScreen` callbacks (`signIn`, `register`, `signInAnonymously`, `signOut`) are properly exposed.

#### [MODIFY] [FloraGuideApp.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/ui/FloraGuideApp.kt)
- Observe `authState` from the ViewModel.
- Implement conditional rendering:
    - If `authState !is AuthState.Authenticated`, show `AuthScreen` as the root UI.
    - If `authState is AuthState.Authenticated`, show the main `Scaffold` with navigation.
- Add `AppScreen.ACCOUNT` handling to the `Scaffold` content and `FloraGuideNavigationBar`.

---

## Verification Plan

### Manual Verification
- **Launch App**: Verify the app starts on the `AuthScreen`.
- **Sign In/Register**: Verify that successful authentication navigates to the `HomeScreen`.
- **Anonymous Login**: Verify that "Continue as Anonymous Guest" allows access to the app.
- **Account Tab**: Navigate to the "Account" tab and verify the profile information is displayed.
- **Sign Out**: Click "Sign Out" and verify the app returns to the `AuthScreen`.
- **Back Button**: Verify that the back button behavior remains consistent (e.g., backing out of Scan returns to Home, not Auth screen if already logged in).
