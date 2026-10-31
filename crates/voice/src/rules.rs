//! The two rules of thread #167, in one place: which mic hears Aviv, and whether Jarvis speaks or
//! writes. Pure, so every case is a test; the app only reports what it senses and does what comes
//! back, and logs [`Pick::rule`], so one real run says which rule picked the mic.
//!
//! Mic: the Lark if it's plugged in, otherwise the phone's with the screen on and the AirPods' with
//! it off. Jarvis speaks when the screen is off, or when the Lark and the AirPods are both connected;
//! a Lark left plugged in with no AirPods doesn't make the phone talk aloud. The AirPods' mic stays
//! shut while music plays: opening it would drop the music to call quality. A press of the stem
//! pauses the music, which is what opens it.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Sense {
    pub lark: bool,
    /// AirPods or any other headset with a mic of its own.
    pub airpods: bool,
    pub screen: bool,
    /// Another app's music, held over the gaps between songs.
    pub music: bool,
    pub muted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mic {
    Lark,
    Phone,
    AirPods,
    /// Muted, or the AirPods' mic while music plays: nothing is recording.
    Shut,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pick {
    pub mic: Mic,
    pub speak: bool,
    /// Android's call route: only for what truly needs it. It forces the AirPods into call quality.
    pub telecom: bool,
    /// Which rule, for the log.
    pub rule: &'static str,
}

pub fn pick(s: Sense) -> Pick {
    let speak = !s.screen || s.lark && s.airpods;
    let (mic, rule) = match () {
        _ if s.muted => (Mic::Shut, "muted"),
        _ if s.lark => (Mic::Lark, "lark plugged in"),
        _ if s.screen => (Mic::Phone, "screen on: phone mic"),
        _ if s.airpods && s.music => (Mic::Shut, "screen off, music plays: airpods mic shut"),
        _ if s.airpods => (Mic::AirPods, "screen off: airpods mic"),
        _ => (Mic::Phone, "screen off, no airpods: phone mic"),
    };
    let telecom = match mic {
        Mic::AirPods => true,
        Mic::Phone => speak,
        Mic::Lark | Mic::Shut => false,
    };
    Pick { mic, speak, telecom, rule }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(lark: bool, airpods: bool, screen: bool, music: bool) -> Pick {
        pick(Sense { lark, airpods, screen, music, muted: false })
    }

    #[test]
    fn the_lark_wins_the_mic_and_never_takes_the_call_route() {
        for (airpods, screen, music) in [(false, false, false), (false, true, false), (true, false, true), (true, true, true)] {
            let p = at(true, airpods, screen, music);
            assert_eq!((p.mic, p.telecom), (Mic::Lark, false), "{airpods} {screen} {music}");
        }
    }

    #[test]
    fn the_lark_and_the_airpods_speak_even_with_the_screen_on() {
        assert!(at(true, true, true, false).speak);
    }

    #[test]
    fn a_lark_left_plugged_in_without_airpods_does_not_talk_aloud() {
        assert!(!at(true, false, true, false).speak);
    }

    #[test]
    fn screen_on_is_the_phone_mic_and_leaves_the_airpods_their_music() {
        let p = at(false, true, true, true);
        assert_eq!(p, Pick { mic: Mic::Phone, speak: false, telecom: false, rule: "screen on: phone mic" });
    }

    #[test]
    fn screen_off_with_airpods_records_from_them_on_the_call_route() {
        let p = at(false, true, false, false);
        assert_eq!(p, Pick { mic: Mic::AirPods, speak: true, telecom: true, rule: "screen off: airpods mic" });
    }

    #[test]
    fn music_keeps_the_airpods_mic_shut_and_their_quality_up_and_jarvis_still_speaks() {
        let p = at(false, true, false, true);
        assert_eq!((p.mic, p.speak, p.telecom), (Mic::Shut, true, false));
    }

    #[test]
    fn screen_off_alone_is_todays_route() {
        let p = at(false, false, false, false);
        assert_eq!((p.mic, p.speak, p.telecom), (Mic::Phone, true, true));
    }

    #[test]
    fn mute_shuts_every_mic_and_changes_nothing_else() {
        for (lark, airpods, screen, music) in [(true, true, true, false), (false, true, false, false), (false, false, true, true)] {
            let p = pick(Sense { lark, airpods, screen, music, muted: true });
            assert_eq!((p.mic, p.telecom), (Mic::Shut, false));
            assert_eq!(p.speak, at(lark, airpods, screen, music).speak);
        }
    }
}
