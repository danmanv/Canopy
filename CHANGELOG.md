# Changelog

Write every entry in ASD-STE100 Simplified Technical English (STE):

- Use only the approved words of the STE dictionary, with their approved meanings. You can also use technical names: Alder, Stremio, add-on, subtitle, and the names that Alder shows on the screen.
- Write one idea in each sentence. Write 20 words or fewer in an instruction and 25 words or fewer in a description.
- Use the active voice. Use the simple present tense, or the simple past tense for "Before, …".
- Do not use words that end in "-ing", except in technical names.
- Do not use contractions. Write "does not", not "doesn't". Text that Alder shows on the screen stays as it is.
- Write instructions as commands: "Select **Subtitles**", not "**Subtitles** can be selected".
- Use one word for one thing. Use the names that Alder shows on the screen.
- Put the 3 most interesting changes first.

Put changes that are not released under `## Unreleased`. Before each release, read all of `## Unreleased` again and rewrite each entry that does not obey these rules. Then rename that heading to the version number.

## Unreleased

- **Screensaver.** After 5 minutes without a key press, Canopy shows the art of your Continue Watching and My List titles. The time shows on the art. Push any key to stop it. Push **OK** to open the title on the screen. To change the time, select **Settings → Picture & Sound → Screensaver**.
- **TV screensaver.** The TV can also use the Canopy art when the TV is idle. Select **Settings → Picture & Sound → TV screensaver**. Then select **Canopy**.

## 1.2.1

- **Trailers start sooner.** A trailer shows 3 seconds after you stop on a title. Before, it showed after 5 seconds or more.

## 1.2

- **Trailer previews.** Stop on a title for 3 seconds. Its trailer plays behind it without sound. Push a button to stop it. To turn it off, select **Settings → Picture & Sound → Trailer previews**.
- **Up next starts the next episode.** At the end of an episode, **Up next** counts down from 15 seconds. Then the next episode starts. Select **Watch the credits** to stop it. When you pause, the count stops.
- **Play goes straight to the video.** Select **Play**. One loading screen shows the title and its picture until the video starts. Before, two screens with source details showed first. To choose a source yourself, hold OK on **Play**.
- **Canopy updates itself from signed releases.** Each new version is a GitHub release. Canopy shows a message when a new version is available. Select **Install**. To install it later, select **Settings → About → Updates**.
- If an update has a different signature, Canopy tells you to remove Canopy one time and then install the update.
- **Skip intro for anime.** Anime from Kitsu or MyAnimeList also show **Skip intro** and **Skip recap**. The times come from AniSkip.
- **Skip 85 s.** When no one has timed the intro of an episode, **Skip 85 s** shows in the first 3 minutes. Push OK to go 85 seconds forward.
- **Subtitles in your language.** Select **Settings → Subtitles → Subtitle language**. Then select a language. Each profile keeps its own language. Subtitles in that language start with the video. They also start when the audio is in that language.
- **All subtitle languages in the player.** **Audio & subtitles** shows all the languages from your subtitle add-ons. Your subtitle language and English are at the top. Before, Alder showed only the language of the TV and English.
- **All audio languages.** **Settings → Picture & Sound → Audio language** has all the languages. Before, it had only 7. Frequently used languages are at the top.
- **Alder keeps your choices from the player.** Select an audio language or subtitles in **Audio & subtitles**. The next episode starts with your choice. When you start the title again, it also starts with your choice. Each profile keeps its own choices.
- When Alder changes to a different source, your subtitle choice stays.
- **See your subtitles quickly.** When the player controls show, a line under the progress bar shows the audio language and the subtitles. For example: "English · Spanish subtitles".
- In **Audio & subtitles**, each heading shows the choice in use. For example: "Subtitles · Spanish". A dot shows the choice in use in each list.
- Alder matches language names from all sources, for example "es", "spa" and "Spanish". Before, some subtitles and audio tracks did not match.
- **Portuguese (Brazil)** and **Portuguese** are 2 different languages.
- An add-on subtitle in your language does not start the video again.
- Lists in **Settings** are not transparent.
- **Skip forward in more movies.** Alder finds the correct position in files that have no index, for example .ts files. Before, a skip forward on the progress bar started these movies again from the beginning.
- Some sources cannot skip. In these sources, Alder shows a message, and the movie continues. To get a different source, select **Change stream**.
- **Title details with all add-ons.** Alder gets title details from your add-ons in their order, as Stremio does. When an add-on gives no details, Alder uses the next add-on. Before, some users saw "Couldn't load this title's details" on all titles.
- Alder shows a title when an add-on sends an episode that has no id.
- **Sources in your audio language.** Select a language above the source list. Alder shows only the sources in that language. Sources with "Multi" or "Dual" audio show for all languages.
- When **Dubbed releases** is **My language first**, the source list opens on your audio language.
- **Profiles stay after a sign-out.** When Stremio signs a profile out of this TV, Alder keeps the profile. Select the profile and sign in again. The settings and the PIN of the profile stay. Before, Alder removed the profile.
- When you remove a profile, Alder does not sign out a Stremio account that another profile uses.
- Alder tells you when you add a Stremio account that another profile already uses.
- The sign-in page tells you how to connect 2 accounts with 1 phone.
- **Add-ons with an http:// address work.** Alder connects to add-ons and sources with an http:// address, for example add-ons on your home network. Before, all of these failed.
- **Alder tells you why there is no source.** For example, an add-on did not answer, or an add-on found only torrents.
- Alder sends its name and version to add-ons. Before, some add-ons blocked Alder.
- Sources with only Dolby Vision show on all TVs. When your TV cannot show them correctly, they are at the end of the list.
- **What is new after each update.** After Alder updates, Home shows the changes in the new version. Select **See all changes** to read all of them. Select **Settings → About → What's new** to read them again.
- **Updates on Fire TV.** Alder does not stop when it asks for permission to install an update. When the TV has no page for this permission, Alder shows the steps.
- After you let Alder install apps, the update continues. Before, you had to select **Install** again.
- When the download of an update stops or is not complete, Alder tells you. Before, nothing happened.
- Before an update, Alder tells you that it closes. Open Alder again after the update.
- **Remove titles from Continue Watching.** Hold OK on a title in **Continue**. Then select **Remove from Continue Watching**. Stremio also removes it on your other devices. When you watch the title again, it comes back.
- **Put back removed titles.** Select **Settings → Profile → Hidden from Continue Watching**. Then select a title. The title goes back to **Continue** at the position where you stopped. The list shows only titles that you removed on this TV.
- Hold OK on a title in **Continue** now opens a menu. To see all sources, select **Choose a source**.
- **See the debrid service of each source.** The source list shows the service of each source, for example **RD** for Real-Debrid. **⚡** shows that the file is ready and starts immediately.
- The player shows the debrid service of the source that plays, for example "Torrentio · 2160p · Real-Debrid".
- **All catalogs from AIOMetadata show.** Before, Alder did not show a catalog that needs a genre, for example an AIOMetadata catalog that is not on the Stremio home page. Now Alder shows these catalogs, as Stremio does. This also corrects a **Shows** page with no rows.

## 1.1

- Each profile keeps its own streaming add-ons. Add-ons that you add or remove in Stremio show in Alder on the next page.
- Continue Watching shows correctly when Home opens for the first time.
- The **Audio & subtitles** panel in the player opens correctly.
- In **Search**, the selected poster is no longer cut off at the top.

## 1.0

- **Press Play and it plays.** Alder picks a good source and starts it. Hold OK on **Play** to see every source.
- **Automatic fallback.** When a stream fails, Alder tries the next one from the same second.
- **Only what your TV can play.** Alder checks your TV for 4K, HDR, Dolby Vision, HEVC, AV1 and DTS. It hides files that the TV cannot play.
- **Profiles.** Up to 5 profiles, each with its own Stremio account. A PIN is optional.
- **Stream preferences in plain words:** **Best picture**, **Balanced** or **Fast start**.
- **Series.** The next episode keeps the same release and quality. **Skip intro** is built in.
- **Continue Watching on the Google TV home screen.**
- **Frame-rate matching.** The TV changes to the film's frame rate.
- **Sync with Stremio.** Watch progress matches your phone and computer.
- **Updates.** Alder finds new versions on GitHub and installs them.
