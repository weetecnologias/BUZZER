/** Maps catalogue icon keys (configured by admins) to emoji glyphs. Unknown keys fall back to a star. */
const GLYPHS: Record<string, string> = {
  'home-city': '🏢', tools: '🛠️', truck: '🚚', 'shopping-bag': '🛍️', car: '🚗', ticket: '🎟️', party: '🎉', dining: '🍽️',
  wrench: '🔧', heart: '💚', sparkle: '✨', school: '🎓', file: '📄', family: '👨‍👩‍👧', rings: '💍', briefcase: '💼',
  package: '📦', star: '⭐', bank: '🏦', shield: '🛡️', cart: '🛒', gavel: '⚖️', 'id-card': '🪪', cake: '🎂', camera: '📷',
  pipe: '🚰', bolt: '⚡', snow: '❄️', hammer: '🔨', brush: '🖌️', plug: '🔌', bug: '🐜', key: '🔑', home: '🏠', tag: '🏷️',
  spray: '🧽', basket: '🧺', route: '🛵', pills: '💊', shirt: '👕', film: '🎬', temple: '🛕', trophy: '🏏', chef: '👨‍🍳',
  music: '🎵', game: '🎮', scissors: '✂️', spa: '💆', lipstick: '💄', nail: '💅', balloon: '🎈', baby: '👶', gift: '🎁',
  flower: '🌼', saree: '🥻', dish: '🍛', building: '🏛️', video: '🎥', image: '🖼️', drone: '🚁', calculator: '🧮', chart: '📈',
  steering: '🧑‍✈️', hotel: '🏨', beach: '🏖️', map: '🗺️', globe: '🌍', passport: '🛂', fitness: '🧘', stethoscope: '🩺',
  flask: '🧪', book: '📚', graduation: '🎓', broom: '🧹', bike: '🏍️', users: '👥', send: '📮', search: '🔎', clipboard: '📋',
  calendar: '📅', store: '🏪', bulb: '💡', paint: '🎨', nut: '🔩',
};

export function glyph(icon?: string | null): string {
  return (icon && GLYPHS[icon]) || '⭐';
}
