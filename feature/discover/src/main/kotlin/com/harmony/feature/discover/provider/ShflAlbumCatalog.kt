package com.harmony.feature.discover.provider

import com.harmony.feature.discover.model.AlbumGenre.*
import com.harmony.feature.discover.model.DiscoverAlbum

/**
 * A deliberately familiar selection, expanded to 96 verified public album
 * pages on 2026-09-06. This is not a live SHFL API or a copy of its reviews.
 * Notes are original Harmony copy; full recommendations stay on the source.
 * IDs are observed slugs, not generated from titles (Discovery-1 matters).
 */
object ShflAlbumCatalog {
    val albums: List<DiscoverAlbum> = listOf(
        album("Random-Access-Memories", "Random Access Memories", "Daft Punk", 2013,
            setOf(ELECTRONIC, SOUL_POP), listOf("Get Lucky", "Instant Crush", "Lose Yourself to Dance"),
            "Stay beyond the single for patient grooves, studio detail and unexpected guest voices. This is a record that rewards listening in sequence.",
            "5731384752603136_600.jpg", 0xFFCDA861),
        album("Rumours", "Rumours", "Fleetwood Mac", 1977,
            setOf(ROCK, SOUL_POP), listOf("Dreams", "Go Your Own Way", "The Chain"),
            "The familiar hooks are only the beginning. Hear the different voices answer each other, with quieter songs giving the big choruses their weight.",
            "5834170009911296_v1_600.jpg", 0xFFBDA584),
        album("OK-Computer", "OK Computer", "Radiohead", 1997,
            setOf(ROCK), listOf("No Surprises", "Karma Police", "Paranoid Android"),
            "Let the gentle songs and unsettling turns sit together. The contrast makes this a much richer journey than a collection of its singles.",
            "5524610510487552_600.jpg", 0xFF84B0BD),
        album("Back-to-Black", "Back to Black", "Amy Winehouse", 2006,
            setOf(SOUL_POP), listOf("Rehab", "Back to Black", "You Know I'm No Good"),
            "Listen for the wit as much as the heartbreak. The close arrangements and conversational delivery keep the quieter moments just as compelling.",
            "6678140545925120_600.jpg", 0xFF689AB0),
        album("Illmatic", "Illmatic", "Nas", 1994,
            setOf(HIP_HOP), listOf("The World Is Yours", "N.Y. State of Mind"),
            "A compact album with remarkable focus. Follow the street scenes from track to track, and notice how each beat frames a different perspective.",
            "6318307716104192_600.jpg", 0xFFC18C69),
        album("Discovery-1", "Discovery", "Daft Punk", 2001,
            setOf(ELECTRONIC, SOUL_POP), listOf("One More Time", "Harder, Better, Faster, Stronger", "Digital Love"),
            "Start with the celebration, then follow the detours through soft melodies, guitar solos and looping dance grooves. The whole sequence earns its name.",
            "6314174682497024_600.jpg", 0xFFA990C9),
        album("Mezzanine", "Mezzanine", "Massive Attack", 1998,
            setOf(ELECTRONIC), listOf("Teardrop", "Angel"),
            "Give the bass and space time to unfold. Heard together, these tense, slow-moving tracks build a world that one familiar song can only suggest.",
            "5200598446112768_v1_600.jpg", 0xFF91A7A1),
        album("Dummy", "Dummy", "Portishead", 1994,
            setOf(ELECTRONIC), listOf("Glory Box", "Sour Times"),
            "A good late-night listen: clipped beats, dramatic space and a voice that stays close. Let the atmosphere carry you between the recognizable songs.",
            "5802042782121984_600.jpg", 0xFF649FA8),
        album("Is-This-It", "Is This It", "The Strokes", 2001,
            setOf(ROCK), listOf("Last Nite", "Someday"),
            "Short songs, sharp guitar exchanges and barely a pause to lose momentum. Play it from the start and let the familiar singles arrive naturally.",
            "5078475165663232_v1_600.jpg", 0xFFD1A669),
        album("Hounds-of-Love", "Hounds of Love", "Kate Bush", 1985,
            setOf(SOUL_POP, ROCK), listOf("Running Up That Hill (A Deal with God)", "Running Up That Hill", "Hounds of Love"),
            "The pop songs open a door to a far stranger second half. Stay for the longer journey, where individual tracks become parts of a story.",
            "5010632311046144_600.jpg", 0xFFD28FA7),
        album("Violator", "Violator", "Depeche Mode", 1990,
            setOf(ELECTRONIC, SOUL_POP), listOf("Enjoy the Silence", "Personal Jesus"),
            "The singles are a strong invitation, but the spaces between them matter. Dark textures and restrained arrangements make this especially rewarding on headphones.",
            "5095082910810112_600.jpg", 0xFFBD7680),
        album("The-Miseducation-of-Lauryn-Hill", "The Miseducation of Lauryn Hill", "Lauryn Hill", 1998,
            setOf(HIP_HOP, SOUL_POP), listOf("Doo Wop (That Thing)", "Ex-Factor"),
            "Follow the shifts between rapping, singing and reflection. The quieter passages give the familiar songs a context that a singles playlist leaves behind.",
            "6317709641908224_600.jpg", 0xFFB48658),
        album("To-Pimp-a-Butterfly", "To Pimp a Butterfly", "Kendrick Lamar", 2015,
            setOf(HIP_HOP), listOf("Alright", "King Kunta", "i"),
            "Give this one your attention from the opening track. Recurring ideas, changing voices and restless grooves reveal more when heard as a complete arc.",
            "5428675436609536_600.jpg", 0xFFA2AA9E),
        album("The-Dark-Side-of-the-Moon", "The Dark Side of the Moon", "Pink Floyd", 1973,
            setOf(ROCK), listOf("Money", "Time"),
            "Keep the tracks in order and listen to the transitions. The sounds between the songs turn the familiar moments into one continuous experience.",
            "6669553362796544_600.jpg", 0xFF8EB9B0),
        album("Doolittle", "Doolittle", "Pixies", 1989,
            setOf(ROCK), listOf("Here Comes Your Man", "Monkey Gone to Heaven"),
            "The catchy songs lead into wonderfully odd corners. Sudden changes in volume and mood make the full record feel both unpredictable and tightly built.",
            "6493925036523520_600.jpg", 0xFFB5986D),
        album("Demon-Days", "Demon Days", "Gorillaz", 2005,
            setOf(ELECTRONIC, ROCK), listOf("Feel Good Inc.", "DARE", "Dirty Harry"),
            "Let the guest voices and stylistic shifts lead you through. The final stretch brings the scattered moods together in a way the hits alone cannot.",
            "4961286781665280_600.jpg", 0xFF91A67B),
        album("The-Low-End-Theory", "The Low End Theory", "A Tribe Called Quest", 1991,
            setOf(HIP_HOP), listOf("Check the Rhime", "Scenario"),
            "Listen to the bass lines, then the conversation between the voices. The easy flow holds up across a whole album of small rhythmic pleasures.",
            "4956003481157632_600.jpg", 0xFFB07372),
        album("The-Marshall-Mathers-LP", "The Marshall Mathers LP", "Eminem", 2000,
            setOf(HIP_HOP), listOf("Stan", "The Real Slim Shady"),
            "Beyond the singles, follow the changing characters and intricate phrasing. An abrasive, confrontational listen whose storytelling works best with the surrounding tracks.",
            "5393595351695360_600.jpg", 0xFFB39F87),
        album("Purple-Rain", "Purple Rain", "Prince and the Revolution", 1984,
            setOf(SOUL_POP, ROCK), listOf("When Doves Cry", "Purple Rain", "Let's Go Crazy"),
            "Hear the way lean pop, guitar drama and slower moments build toward the finale. The familiar ending lands differently after the entire record.",
            "4970503290748928_v1_600.jpg", 0xFFAE91C9,
            artistAliases = setOf("Prince", "Prince & The Revolution")),
        album("Songs-in-the-Key-of-Life", "Songs in the Key of Life", "Stevie Wonder", 1976,
            setOf(SOUL_POP), listOf("Isn't She Lovely", "Sir Duke"),
            "Make room for a longer listen. Bright hooks, intimate reflections and exploratory arrangements sit side by side, revealing the breadth behind the familiar songs.",
            "5195111382122496_600.jpg", 0xFFD7A36B),
        album("Disintegration", "Disintegration", "The Cure", 1989,
            setOf(ROCK), listOf("Lovesong", "Lullaby", "Pictures of You"),
            "Take your time with the long introductions and returning textures. The compact singles gain a different emotional weight inside the album's wider atmosphere.",
            "5084063970885632_600.jpg", 0xFF8DA293),
        album("Automatic-for-the-People", "Automatic for the People", "R.E.M.", 1992,
            setOf(ROCK), listOf("Everybody Hurts", "Man on the Moon"),
            "A quieter record that grows with a full listen. Follow the acoustic details and reflective turns between the songs that already feel familiar.",
            "4941658290388992_600.jpg", 0xFFB2A585),
        album("The-Score", "The Score", "Fugees", 1996,
            setOf(HIP_HOP, SOUL_POP), listOf("Killing Me Softly with His Song", "Ready or Not"),
            "Stay for the interplay between three distinct voices. Soulful melodies and sharp verses keep reshaping the mood around the songs you may already recognize.",
            "4569390188068864_600.jpg", 0xFFBD835E),
        album("Thriller-1", "Thriller", "Michael Jackson", 1982,
            setOf(SOUL_POP), listOf("Billie Jean", "Beat It", "Thriller"),
            "Try hearing the famous tracks in their original sequence. The softer songs and tight rhythmic details make a familiar record feel surprisingly fresh.",
            "5708150434955264_600.jpg", 0xFFC4A181),
    ).map { album ->
        val genres = when (album.id) {
            "Random-Access-Memories", "Discovery-1", "Violator" -> setOf(ELECTRONIC, POP)
            "Rumours", "Hounds-of-Love" -> setOf(ROCK, POP)
            "Thriller-1" -> setOf(POP, FUNK, SOUL_POP)
            "Purple-Rain" -> setOf(POP, FUNK, ROCK)
            "Back-to-Black" -> setOf(SOUL_POP, RNB)
            "The-Miseducation-of-Lauryn-Hill" -> setOf(HIP_HOP, SOUL_POP, RNB)
            "Songs-in-the-Key-of-Life" -> setOf(SOUL_POP, FUNK)
            "OK-Computer", "Is-This-It", "Doolittle", "Automatic-for-the-People" -> setOf(ROCK, INDIE)
            else -> album.genres
        }
        album.copy(genres = genres)
    } + ShflExtendedCatalog.albums

    val ids: Set<String> = albums.map { it.id }.toSet()

    private fun album(
        id: String, title: String, artist: String, year: Int,
        genres: Set<com.harmony.feature.discover.model.AlbumGenre>, entryTracks: List<String>,
        note: String, coverFile: String, color: Long, artistAliases: Set<String> = emptySet(),
    ) = DiscoverAlbum(id, title, artist, year, genres, entryTracks, note,
        "https://images.theshfl.com/$coverFile", color, artistAliases)
}
