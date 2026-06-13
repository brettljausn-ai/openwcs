// German (Deutsch) - slotting screen (pick faces + block slotting).
export default {
  pickFacesHeading: 'Kommissionierplätze (manuelle Einlagerung · Min/Max)',
  pickFacesIntro:
    'Ordnen Sie einem festen Kommissionierplatz eine Artikelnummer + Mengeneinheit zu. Die Nachschubsteuerung füllt ihn bis Max auf; Wareneingänge können direkt hierher gehen, wenn Direkt-zur-Kommissionierung aktiv ist.',
  colLocation: 'Lagerplatz',
  tipLocation:
    'Der feste Kommissionierplatz (Regal-/Behälterfront), dem diese Artikelnummer zugeordnet ist. Kommissionierer gehen für diesen Artikel immer hierher.',
  colSku: 'Artikelnummer',
  tipSku: 'Der diesem Kommissionierplatz zugeordnete Lagerartikel.',
  colUom: 'Mengeneinheit',
  tipUom: 'Mengeneinheit, in der von dieser Front kommissioniert wird; die Kommissioniermenge wird in diesen Einheiten gezählt.',
  colMin: 'Min',
  tipMin: 'Nachschub-Auslöser: Sinkt der Bestand am Platz auf diesen Wert oder darunter, wird ein Auffüllauftrag erzeugt.',
  colMax: 'Max',
  tipMax: 'Sollfüllstand. Der Nachschub füllt den Platz wieder bis zu dieser Menge auf.',
  colDirect: 'Direkt',
  tipDirect:
    'Wenn aktiv, kann Wareneingangsbestand dieser Artikelnummer direkt an den Kommissionierplatz statt ins Reservelager eingelagert werden.',
  phLocation: 'Lagerplatz',
  phSku: 'Artikelnr.',
  phUom: 'Einheit',
  phMin: 'Min',
  phMax: 'Max',
  directToPick: 'Direkt-zur-Kommissionierung',
  addPickFace: 'Kommissionierplatz hinzufügen',
  blockSlottingHeading: 'Blockeinlagerung (automatisiert ASRS / AutoStore / AMR-GTP)',
  blockSlottingIntro:
    'Ordnen Sie eine Artikelnummer einem Lagerblock zu (dem gesamten Pool, alle Gassen). Die Einlagerungssteuerung wählt den tatsächlichen Lagerplatz je Ladehilfsmittel und gewichtet Geschwindigkeit zum Ausgang, Konsolidierung gleicher Artikel, Gassenredundanz und Füllausgleich.',
  tipBlockSku:
    'Der Lagerartikel, der einem automatisierten Lagerblock (ASRS- / AutoStore- / AMR-GTP-Pool) zugeordnet wird.',
  colBlock: 'Block',
  tipBlock:
    'Der Lagerblock (gesamter Pool, alle Gassen), in dem diese Artikelnummer gelagert werden darf. Die Einlagerungssteuerung wählt den genauen Lagerplatz je Ladehilfsmittel.',
  colVelocity: 'Umschlag',
  tipVelocity:
    'Umschlagsklasse, die bestimmt, wie nah am Ausgang/an der Kommissionierung der Artikel gelagert wird. A = Schnelldreher, C = Langsamdreher.',
  colConsolidate: 'Konsolidieren',
  tipConsolidate:
    'Wenn aktiv, bevorzugt die Steuerung das Zusammenlegen gleicher Artikel (weniger, dichtere Lagerplätze) statt sie zu verteilen.',
  colMinAisles: 'Min. Gassen',
  tipMinAisles:
    'Mindestzahl unterschiedlicher Gassen, über die diese Artikelnummer verteilt werden muss, zur Redundanz, falls eine Gasse ausfällt.',
  colMaxAislePct: 'Max. Gassen-%',
  tipMaxAislePct:
    'Obergrenze für den Anteil einer Gasse, den eine einzelne Artikelnummer belegen darf, zum Ausgleich der Gassen (0 bis 1).',
  phBlock: 'Block…',
  velocityClass: 'Umschlagsklasse',
  consolidate: 'konsolidieren',
  phMinAisles: 'Min. Gassen',
  phMaxAislePct: 'Max. Gassen-%',
  addBlockSlotting: 'Blockeinlagerung hinzufügen',
}
