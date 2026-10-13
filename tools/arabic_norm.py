"""Arabic normalization policy for Khumasi.

This module is the reference implementation of the published policy
(docs/ARABIC_RULES.md). The Kotlin implementation in
app/src/main/java/com/khumasi/game/core/ArabicNormalizer.kt must produce
identical results; tools/build_dictionary.py writes test vectors that the
Kotlin unit tests check against.
"""
import re
import unicodedata

# Letters that may appear in a normalized word (33 letters).
LETTERS = 'ابتثجحخدذرزسشصضطظعغفقكلمنهوي' + 'ءؤئىة'
LETTER_SET = set(LETTERS)

# Diacritics, Quranic marks, tatweel and invisible formatting characters.
_REMOVE = re.compile(
    '[ؐ-ًؚ-ٰٟۖ-ۜ۟-۪ۨ-ۭ'
    'ـ​-‏‪-‮⁦-⁩﻿\\s]'
)
# All alef forms collapse to bare alef. Hamza on waw/yeh and standalone hamza stay distinct.
_ALEF = str.maketrans({c: 'ا' for c in 'أإآٱٲٳ'})


def normalize(text: str) -> str:
    text = unicodedata.normalize('NFKC', text)
    text = _REMOVE.sub('', text)
    return text.translate(_ALEF)


def is_clean(word: str) -> bool:
    """True when a normalized word contains only the 33 game letters."""
    return bool(word) and all(c in LETTER_SET for c in word)
