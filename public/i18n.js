/* openWCS public site — lightweight client-side i18n (no build step).
 * Detects the visitor's browser language and lands on it (en/de/fr/es),
 * with a manual selector that persists the choice in localStorage.
 * English markup in index.html is the default/fallback, so the page is
 * fully meaningful with JS disabled and for crawlers. */
(function () {
  var SUPPORTED = ['en', 'de', 'fr', 'es'];

  var I18N = {
    en: {
      __title: 'openWCS — the open-source Warehouse Control System you actually control',
      navCap: 'Capabilities', navWhy: 'Why open', navCmp: 'vs. locked-in WCS', navArch: 'Architecture',
      btnGitHub: 'View on GitHub',
      heroEyebrow: 'Open-source Warehouse Control System',
      heroTitle: 'The WCS you<br /><span class="lime">actually control.</span>',
      heroLead: 'openWCS orchestrates your automation — conveyors, ASRS, AMRs, AutoStore — and the real-time flow of goods, sitting between your WMS/ERP and the machines. Open, vendor-neutral, and yours to change.',
      heroPrimary: 'View on GitHub', heroSecondary: 'Read the docs',
      heroNote: 'AGPL-3.0 open source · self-host · no per-flow license fees',
      flowConveyor: 'Conveyor',
      capEyebrow: 'What openWCS does',
      capTitle: 'An open WCS that does the <span class="lime">real work</span> — and lets you change it.',
      capIntro: 'openWCS is a full warehouse control system built in the open: the same capabilities you expect from a commercial WCS, with none of the lock-in. Run it yourself, read every line, and adapt it to your building.',
      c1t: 'Equipment', c1h: 'Vendor-neutral by design', c1p: 'A uniform device contract with adapters for conveyors, ASRS, AMRs and AutoStore. Add a vendor without re-architecting — their protocol stays in the adapter.',
      c2t: 'Conveyor routing', c2h: 'Routing you can see', c2p: 'Draw the conveyor topology in a visual editor, route handling units by shortest path through their targets, enforce loop limits — and even learn the layout from live traffic.',
      c3t: 'Processes', c3h: 'Design your own flows', c3p: 'A built-in BPMN designer: model goods-in, outbound and cycle-count processes in the browser, deploy instantly, and have their steps originate real WCS work.',
      c4t: 'Inventory', c4h: 'Real-time, event-sourced stock', c4p: 'Durable stock projected from an append-only transaction log, with location-scoped availability and reservations. Rebuildable, auditable, and yours to query.',
      c5t: 'Outbound', c5h: 'Allocation, cubing &amp; labels', c5p: 'Pick-location allocation with UoM breakdown, multi-size cubing into shippers (largest-first, line-traceable), per-carton dispatch labels, and batch picking.',
      c6t: 'Host API', c6h: 'Integrate your WMS once', c6p: 'One canonical, vendor-neutral Host API for orders, ASNs and confirmations. SAP and Manhattan adapters translate into it; idempotency and webhooks included.',
      c7t: 'Security', c7h: 'Secure by design', c7p: 'Gateway JWT validation, per-endpoint role-based access control, and Keycloak — all toggleable so you can start simple and lock down for production.',
      c8t: 'Architecture', c8h: 'Built to evolve', c8p: 'Independent microservices, schema-per-service Postgres, a Kafka event backbone, and contract-first APIs — so you can extend or replace any part.',
      c9t: 'Open', c9h: 'No lock-in, ever', c9p: 'Open source. Self-host on your own infrastructure, fork it, and integrate whoever you like. Leaving is never a rip-and-replace.',
      c10t: 'Slotting', c10h: 'Slotting &amp; replenishment', c10p: 'Configurable put-away for ASRS/AutoStore/AMR blocks — velocity-to-exit, multi-deep same-SKU lanes, aisle redundancy and fill balancing — plus fixed pick-face min/max with opportunistic replenishment and direct-to-pick.',
      whyEyebrow: 'The problem',
      whyTitle: 'Your WCS shouldn’t be a <span class="lime">black box</span>.',
      whyIntro: 'Most warehouse control systems are shipped by the integrator who installed your equipment — closed software, tied to their stack and their contract. The logic that routes your totes and allocates your stock is hidden, and every change goes through them. That’s fine until you need to move fast.',
      p1t: 'Opaque', p1h: 'Black-box logic', p1p: 'You can’t see how routing, allocation or cubing decisions are actually made — so you can’t trust, tune, or debug them.',
      p2t: 'Slow', p2h: 'Every change is a quote', p2p: 'A new pick path or process tweak means a change request, a price, and a place in someone else’s queue.',
      p3t: 'Locked in', p3h: 'One vendor, one stack', p3p: 'The WCS is bound to the integrator’s preferred hardware and protocols. Adding a new device family means going back to them.',
      p4t: 'Hostage', p4h: 'Your data isn’t yours', p4p: 'Operational history and live state live inside a system you can’t query or export on your terms.',
      p5t: 'Risk', p5h: 'Their roadmap, not yours', p5p: 'Their priorities, their pricing, their support window. Switching later means a rip-and-replace project.',
      p6t: 'Cost', p6h: 'Pay to keep the lights on', p6p: 'Licenses, support contracts and change fees compound — for software you’ll never own or fully see.',
      cmpEyebrow: 'openWCS vs. a locked-in integrator WCS',
      cmpTitle: 'Same control floor. <span class="lime">Different rules.</span>',
      cmpFeat: 'Capability', cmpThem: 'Typical integrator WCS', cmpUs: 'openWCS',
      r1f: 'Source &amp; ownership', r1d: 'Closed binary you license', r1u: 'Open source you run and own',
      r2f: 'Change a process or pick path', r2d: 'Paid change request, weeks of lead time', r2u: 'Draw it in the BPMN/topology editor and deploy yourself',
      r3f: 'Equipment vendors', r3d: 'Tied to the integrator’s preferred hardware', r3u: 'Vendor-neutral adapters over a uniform device contract',
      r4f: 'Conveyor routing logic', r4d: 'Opaque PLC / black-box rules', r4u: 'Visual, shortest-path, editable — and learnable from traffic',
      r5f: 'WMS / ERP integration', r5d: 'Bespoke interface, re-built per integrator', r5u: 'One canonical Host API; SAP/Manhattan adapters translate in',
      r6f: 'Your operational data', r6d: 'Trapped inside the vendor’s system', r6u: 'Event-sourced log + Postgres you host and query',
      r7f: 'Switching / exit', r7d: 'Rip-and-replace project', r7u: 'No license lock-in — fork, self-host, extend',
      r8f: 'Cost model', r8d: 'License + support + per-change fees', r8u: 'Free core; pay only for hardware, ops and the partners you choose',
      r9f: 'Slotting &amp; replenishment', r9d: 'Vendor’s fixed rules, opaque and hard to tune', r9u: 'Configurable put-away scoring + min/max replenishment you can read and change',
      archEyebrow: 'Under the hood', archTitle: 'Transparent by construction.',
      archIntro: 'A clean microservice architecture you can read end to end: each service owns its data, movements are events on an append-only log, and equipment lives behind adapters.',
      a1t: 'Java 21 · Spring Boot', a1h: 'Domain services', a1p: 'master-data, inventory, orders, allocation, slotting, goods-to-person stations, flow-orchestrator, txlog, IAM — each independently deployable.',
      a2t: 'Go', a2h: 'Equipment adapters', a2p: 'Lightweight drivers that speak each vendor’s protocol and the uniform device contract.',
      a3t: 'Postgres · Kafka', a3h: 'Event backbone', a3p: 'Schema-per-service stores and an append-only event log — auditable, replayable, rebuildable.',
      ctaEyebrow: 'Built in the open', ctaTitle: 'Take control of your control system.',
      ctaP: 'Read the code, run the stack with Docker Compose, and shape it to your warehouse. Contributions welcome.',
      ctaPrimary: 'View on GitHub', ctaSecondary: 'Read the docs',
      footerTag: 'openWCS — open-source Warehouse Control System · AGPL-3.0.'
    },

    de: {
      __title: 'openWCS — das Open-Source Warehouse Control System, das du wirklich kontrollierst',
      navCap: 'Funktionen', navWhy: 'Warum offen', navCmp: 'vs. proprietäres WCS', navArch: 'Architektur',
      btnGitHub: 'Auf GitHub ansehen',
      heroEyebrow: 'Open-Source Warehouse Control System',
      heroTitle: 'Das WCS, das du<br /><span class="lime">wirklich kontrollierst.</span>',
      heroLead: 'openWCS orchestriert deine Automatisierung — Fördertechnik, AKL, AMRs, AutoStore — und den Echtzeit-Warenfluss, zwischen deinem WMS/ERP und den Maschinen. Offen, herstellerneutral und von dir veränderbar.',
      heroPrimary: 'Auf GitHub ansehen', heroSecondary: 'Doku lesen',
      heroNote: 'AGPL-3.0 Open Source · selbst hosten · keine Lizenzgebühren pro Ablauf',
      flowConveyor: 'Förderer',
      capEyebrow: 'Was openWCS kann',
      capTitle: 'Ein WCS, das die <span class="lime">echte Arbeit</span> macht — und dich ändern lässt.',
      capIntro: 'openWCS ist ein vollständiges Warehouse Control System, offen entwickelt: dieselben Funktionen wie ein kommerzielles WCS, ohne die Abhängigkeit. Selbst betreiben, jede Zeile lesen und an dein Lager anpassen.',
      c1t: 'Equipment', c1h: 'Herstellerneutral by Design', c1p: 'Ein einheitlicher Geräte-Vertrag mit Adaptern für Fördertechnik, AKL, AMRs und AutoStore. Einen Hersteller hinzufügen, ohne neu zu architektieren — sein Protokoll bleibt im Adapter.',
      c2t: 'Förderlogik', c2h: 'Routing, das du siehst', c2p: 'Zeichne die Fördertopologie in einem visuellen Editor, leite Ladeeinheiten auf kürzestem Weg durch ihre Ziele, erzwinge Loop-Limits — und lerne das Layout sogar aus dem Live-Verkehr.',
      c3t: 'Prozesse', c3h: 'Eigene Abläufe entwerfen', c3p: 'Ein integrierter BPMN-Designer: Wareneingang, Warenausgang und Inventur im Browser modellieren, sofort deployen und ihre Schritte echte WCS-Arbeit auslösen lassen.',
      c4t: 'Bestand', c4h: 'Echtzeit, Event-Sourced', c4p: 'Dauerhafter Bestand, projiziert aus einem append-only Transaktionslog, mit lagerplatzbezogener Verfügbarkeit und Reservierungen. Wiederherstellbar, prüfbar und von dir abfragbar.',
      c5t: 'Versand', c5h: 'Allokation, Kartonierung &amp; Labels', c5p: 'Kommissionierplatz-Allokation mit ME-Aufschlüsselung, Multi-Size-Kartonierung in Versandeinheiten (größtes zuerst, positionsgenau rückverfolgbar), Versandlabels je Karton und Batch-Picking.',
      c6t: 'Host-API', c6h: 'WMS einmal anbinden', c6p: 'Eine kanonische, herstellerneutrale Host-API für Aufträge, Avise und Bestätigungen. SAP- und Manhattan-Adapter übersetzen hinein; Idempotenz und Webhooks inklusive.',
      c7t: 'Sicherheit', c7h: 'Sicher by Design', c7p: 'JWT-Prüfung am Gateway, rollenbasierte Zugriffskontrolle je Endpunkt und Keycloak — alles abschaltbar, damit du einfach starten und für die Produktion absichern kannst.',
      c8t: 'Architektur', c8h: 'Gebaut, um zu wachsen', c8p: 'Unabhängige Microservices, Schema-pro-Service-Postgres, ein Kafka-Event-Backbone und Contract-First-APIs — so kannst du jeden Teil erweitern oder ersetzen.',
      c9t: 'Offen', c9h: 'Keine Abhängigkeit, nie', c9p: 'Open Source. Auf eigener Infrastruktur betreiben, forken und beliebige Partner anbinden. Ein Wechsel ist nie ein Rip-and-Replace.',
      c10t: 'Slotting', c10h: 'Slotting &amp; Nachschub', c10p: 'Konfigurierbare Einlagerung für ASRS/AutoStore/AMR-Blöcke — Nähe zum Ausgang, mehrfachtiefe Gassen mit gleicher SKU, Gang-Redundanz und Füllstandsausgleich — plus feste Kommissionierplätze mit Min/Max, opportunistischem Nachschub und Direkt-zum-Platz.',
      whyEyebrow: 'Das Problem',
      whyTitle: 'Dein WCS sollte keine <span class="lime">Blackbox</span> sein.',
      whyIntro: 'Die meisten Warehouse Control Systems kommen vom Integrator, der deine Anlage installiert hat — geschlossene Software, gebunden an seinen Stack und seinen Vertrag. Die Logik, die deine Behälter routet und deinen Bestand allokiert, ist verborgen, und jede Änderung läuft über ihn. Das ist in Ordnung, bis du schnell handeln musst.',
      p1t: 'Undurchsichtig', p1h: 'Blackbox-Logik', p1p: 'Du siehst nicht, wie Routing-, Allokations- oder Kartonierungsentscheidungen wirklich getroffen werden — also kannst du ihnen weder trauen noch sie tunen oder debuggen.',
      p2t: 'Langsam', p2h: 'Jede Änderung ist ein Angebot', p2p: 'Ein neuer Kommissionierweg oder eine Prozessanpassung bedeutet einen Change Request, einen Preis und einen Platz in fremder Warteschlange.',
      p3t: 'Gebunden', p3h: 'Ein Hersteller, ein Stack', p3p: 'Das WCS ist an die bevorzugte Hardware und Protokolle des Integrators gebunden. Eine neue Gerätefamilie? Zurück zu ihm.',
      p4t: 'Geisel', p4h: 'Deine Daten gehören nicht dir', p4p: 'Betriebshistorie und Live-Zustand liegen in einem System, das du nicht zu deinen Bedingungen abfragen oder exportieren kannst.',
      p5t: 'Risiko', p5h: 'Ihre Roadmap, nicht deine', p5p: 'Ihre Prioritäten, ihre Preise, ihr Support-Fenster. Ein späterer Wechsel bedeutet ein Rip-and-Replace-Projekt.',
      p6t: 'Kosten', p6h: 'Zahlen, damit das Licht anbleibt', p6p: 'Lizenzen, Supportverträge und Änderungsgebühren summieren sich — für Software, die dir nie gehört und die du nie ganz siehst.',
      cmpEyebrow: 'openWCS vs. ein proprietäres Integrator-WCS',
      cmpTitle: 'Gleiche Steuerungsebene. <span class="lime">Andere Regeln.</span>',
      cmpFeat: 'Funktion', cmpThem: 'Typisches Integrator-WCS', cmpUs: 'openWCS',
      r1f: 'Quellcode &amp; Eigentum', r1d: 'Geschlossenes Binary, das du lizenzierst', r1u: 'Open Source, das du betreibst und besitzt',
      r2f: 'Prozess oder Pickpfad ändern', r2d: 'Kostenpflichtiger Change Request, Wochen Vorlauf', r2u: 'Im BPMN-/Topologie-Editor zeichnen und selbst deployen',
      r3f: 'Geräte-Hersteller', r3d: 'An die bevorzugte Hardware des Integrators gebunden', r3u: 'Herstellerneutrale Adapter über einen einheitlichen Geräte-Vertrag',
      r4f: 'Förder-Routing-Logik', r4d: 'Undurchsichtige SPS-/Blackbox-Regeln', r4u: 'Visuell, kürzester Weg, editierbar — und aus Verkehr erlernbar',
      r5f: 'WMS-/ERP-Integration', r5d: 'Maßgeschneiderte Schnittstelle, je Integrator neu gebaut', r5u: 'Eine kanonische Host-API; SAP-/Manhattan-Adapter übersetzen hinein',
      r6f: 'Deine Betriebsdaten', r6d: 'Im System des Herstellers gefangen', r6u: 'Event-Sourced-Log + Postgres, das du hostest und abfragst',
      r7f: 'Wechsel / Ausstieg', r7d: 'Rip-and-Replace-Projekt', r7u: 'Keine Lizenzbindung — forken, selbst hosten, erweitern',
      r8f: 'Kostenmodell', r8d: 'Lizenz + Support + Gebühren je Änderung', r8u: 'Kostenloser Kern; zahle nur für Hardware, Betrieb und gewählte Partner',
      r9f: 'Slotting &amp; Nachschub', r9d: 'Feste, undurchsichtige Regeln des Anbieters', r9u: 'Konfigurierbares Einlagerungs-Scoring + Min/Max-Nachschub, les- und änderbar',
      archEyebrow: 'Unter der Haube', archTitle: 'Transparent von Grund auf.',
      archIntro: 'Eine saubere Microservice-Architektur, die du von Ende zu Ende lesen kannst: Jeder Service besitzt seine Daten, Bewegungen sind Events auf einem append-only Log, und Equipment liegt hinter Adaptern.',
      a1t: 'Java 21 · Spring Boot', a1h: 'Domänen-Services', a1p: 'master-data, inventory, orders, allocation, slotting, Goods-to-Person-Stationen, flow-orchestrator, txlog, IAM — jeder unabhängig deploybar.',
      a2t: 'Go', a2h: 'Geräte-Adapter', a2p: 'Leichtgewichtige Treiber, die das Protokoll jedes Herstellers und den einheitlichen Geräte-Vertrag sprechen.',
      a3t: 'Postgres · Kafka', a3h: 'Event-Backbone', a3p: 'Schema-pro-Service-Speicher und ein append-only Event-Log — prüfbar, wiederholbar, wiederherstellbar.',
      ctaEyebrow: 'Offen entwickelt', ctaTitle: 'Übernimm die Kontrolle über dein Steuerungssystem.',
      ctaP: 'Lies den Code, starte den Stack mit Docker Compose und forme ihn für dein Lager. Beiträge willkommen.',
      ctaPrimary: 'Auf GitHub ansehen', ctaSecondary: 'Doku lesen',
      footerTag: 'openWCS — Open-Source Warehouse Control System · AGPL-3.0.'
    },

    fr: {
      __title: 'openWCS — le Warehouse Control System open source que vous contrôlez vraiment',
      navCap: 'Fonctionnalités', navWhy: 'Pourquoi l’ouvert', navCmp: 'vs. WCS verrouillé', navArch: 'Architecture',
      btnGitHub: 'Voir sur GitHub',
      heroEyebrow: 'Warehouse Control System open source',
      heroTitle: 'Le WCS que vous<br /><span class="lime">contrôlez vraiment.</span>',
      heroLead: 'openWCS orchestre votre automatisation — convoyeurs, ASRS, AMR, AutoStore — et le flux de marchandises en temps réel, entre votre WMS/ERP et les machines. Ouvert, indépendant des fournisseurs et modifiable à votre guise.',
      heroPrimary: 'Voir sur GitHub', heroSecondary: 'Lire la doc',
      heroNote: 'Open source AGPL-3.0 · auto-hébergé · aucuns frais de licence par flux',
      flowConveyor: 'Convoyeur',
      capEyebrow: 'Ce que fait openWCS',
      capTitle: 'Un WCS qui fait le <span class="lime">vrai travail</span> — et vous laisse le changer.',
      capIntro: 'openWCS est un Warehouse Control System complet conçu en open source : les mêmes capacités qu’un WCS commercial, sans le verrouillage. À exécuter vous-même, à lire ligne par ligne et à adapter à votre site.',
      c1t: 'Équipement', c1h: 'Indépendant des fournisseurs', c1p: 'Un contrat d’appareil uniforme avec des adaptateurs pour convoyeurs, ASRS, AMR et AutoStore. Ajoutez un fournisseur sans réarchitecturer — son protocole reste dans l’adaptateur.',
      c2t: 'Routage convoyeur', c2h: 'Un routage que vous voyez', c2p: 'Dessinez la topologie des convoyeurs dans un éditeur visuel, acheminez les unités de manutention au plus court chemin vers leurs cibles, imposez des limites de boucle — et apprenez même la disposition à partir du trafic réel.',
      c3t: 'Processus', c3h: 'Concevez vos propres flux', c3p: 'Un concepteur BPMN intégré : modélisez la réception, l’expédition et l’inventaire dans le navigateur, déployez instantanément et faites de leurs étapes de véritables tâches WCS.',
      c4t: 'Stock', c4h: 'Stock temps réel, event-sourcé', c4p: 'Un stock durable projeté depuis un journal de transactions append-only, avec disponibilité et réservations par emplacement. Reconstructible, auditable et interrogeable.',
      c5t: 'Expédition', c5h: 'Allocation, cubage et étiquettes', c5p: 'Allocation des emplacements de prélèvement avec décomposition d’UM, cubage multi-tailles dans les colis (le plus grand d’abord, traçable par ligne), étiquettes d’expédition par colis et préparation par lot.',
      c6t: 'API hôte', c6h: 'Intégrez votre WMS une fois', c6p: 'Une API hôte canonique et indépendante des fournisseurs pour les commandes, les avis et les confirmations. Les adaptateurs SAP et Manhattan s’y traduisent ; idempotence et webhooks inclus.',
      c7t: 'Sécurité', c7h: 'Sécurisé par conception', c7p: 'Validation JWT à la passerelle, contrôle d’accès par rôle et par endpoint, et Keycloak — le tout activable, pour démarrer simplement puis verrouiller en production.',
      c8t: 'Architecture', c8h: 'Conçu pour évoluer', c8p: 'Microservices indépendants, Postgres avec un schéma par service, un backbone d’événements Kafka et des API contract-first — pour étendre ou remplacer chaque partie.',
      c9t: 'Ouvert', c9h: 'Aucun verrouillage, jamais', c9p: 'Open source. Auto-hébergez sur votre propre infrastructure, forkez et intégrez qui vous voulez. Partir n’est jamais un projet de remplacement total.',
      c10t: 'Slotting', c10h: 'Slotting &amp; réapprovisionnement', c10p: 'Rangement configurable pour les blocs ASRS/AutoStore/AMR — proximité de la sortie, allées multi-profondeur mono-SKU, redondance d’allée et équilibrage du remplissage — plus des emplacements de prélèvement fixes en min/max avec réapprovisionnement opportuniste et direct-to-pick.',
      whyEyebrow: 'Le problème',
      whyTitle: 'Votre WCS ne devrait pas être une <span class="lime">boîte noire</span>.',
      whyIntro: 'La plupart des warehouse control systems sont livrés par l’intégrateur qui a installé votre équipement — un logiciel fermé, lié à sa pile et à son contrat. La logique qui achemine vos bacs et alloue votre stock est cachée, et chaque changement passe par lui. C’est acceptable jusqu’à ce que vous deviez aller vite.',
      p1t: 'Opaque', p1h: 'Logique en boîte noire', p1p: 'Vous ne voyez pas comment les décisions de routage, d’allocation ou de cubage sont réellement prises — impossible de leur faire confiance, de les régler ou de les déboguer.',
      p2t: 'Lent', p2h: 'Chaque changement est un devis', p2p: 'Un nouveau chemin de prélèvement ou un ajustement de processus, c’est une demande de modification, un prix et une place dans la file d’attente d’un autre.',
      p3t: 'Verrouillé', p3h: 'Un fournisseur, une pile', p3p: 'Le WCS est lié au matériel et aux protocoles préférés de l’intégrateur. Ajouter une nouvelle famille d’appareils, c’est revenir vers lui.',
      p4t: 'Otage', p4h: 'Vos données ne sont pas les vôtres', p4p: 'L’historique d’exploitation et l’état en direct vivent dans un système que vous ne pouvez ni interroger ni exporter à vos conditions.',
      p5t: 'Risque', p5h: 'Sa feuille de route, pas la vôtre', p5p: 'Ses priorités, ses tarifs, sa fenêtre de support. Changer plus tard, c’est un projet de remplacement total.',
      p6t: 'Coût', p6h: 'Payer pour garder la lumière allumée', p6p: 'Licences, contrats de support et frais de modification s’accumulent — pour un logiciel que vous ne posséderez ni ne verrez jamais entièrement.',
      cmpEyebrow: 'openWCS vs. un WCS d’intégrateur verrouillé',
      cmpTitle: 'Même salle de contrôle. <span class="lime">Règles différentes.</span>',
      cmpFeat: 'Capacité', cmpThem: 'WCS d’intégrateur typique', cmpUs: 'openWCS',
      r1f: 'Code source et propriété', r1d: 'Binaire fermé sous licence', r1u: 'Open source que vous exécutez et possédez',
      r2f: 'Changer un processus ou un chemin', r2d: 'Demande payante, des semaines de délai', r2u: 'Dessinez-le dans l’éditeur BPMN/topologie et déployez vous-même',
      r3f: 'Fournisseurs d’équipement', r3d: 'Lié au matériel préféré de l’intégrateur', r3u: 'Adaptateurs indépendants sur un contrat d’appareil uniforme',
      r4f: 'Logique de routage convoyeur', r4d: 'Règles d’automate / boîte noire opaques', r4u: 'Visuel, plus court chemin, éditable — et apprenable depuis le trafic',
      r5f: 'Intégration WMS/ERP', r5d: 'Interface sur mesure, refaite par intégrateur', r5u: 'Une API hôte canonique ; les adaptateurs SAP/Manhattan s’y traduisent',
      r6f: 'Vos données d’exploitation', r6d: 'Piégées dans le système du fournisseur', r6u: 'Journal event-sourcé + Postgres que vous hébergez et interrogez',
      r7f: 'Changement / sortie', r7d: 'Projet de remplacement total', r7u: 'Aucun verrouillage de licence — forkez, auto-hébergez, étendez',
      r8f: 'Modèle de coût', r8d: 'Licence + support + frais par changement', r8u: 'Cœur gratuit ; payez seulement le matériel, l’exploitation et les partenaires choisis',
      r9f: 'Slotting &amp; réapprovisionnement', r9d: 'Règles figées du fournisseur, opaques et difficiles à régler', r9u: 'Scoring de rangement configurable + réapprovisionnement min/max lisible et modifiable',
      archEyebrow: 'Sous le capot', archTitle: 'Transparent par construction.',
      archIntro: 'Une architecture microservices propre que vous pouvez lire de bout en bout : chaque service possède ses données, les mouvements sont des événements sur un journal append-only, et l’équipement vit derrière des adaptateurs.',
      a1t: 'Java 21 · Spring Boot', a1h: 'Services métier', a1p: 'master-data, inventory, orders, allocation, slotting, stations goods-to-person, flow-orchestrator, txlog, IAM — chacun déployable indépendamment.',
      a2t: 'Go', a2h: 'Adaptateurs d’équipement', a2p: 'Pilotes légers qui parlent le protocole de chaque fournisseur et le contrat d’appareil uniforme.',
      a3t: 'Postgres · Kafka', a3h: 'Backbone d’événements', a3p: 'Des stores avec un schéma par service et un journal d’événements append-only — auditable, rejouable, reconstructible.',
      ctaEyebrow: 'Conçu en open source', ctaTitle: 'Reprenez le contrôle de votre système de contrôle.',
      ctaP: 'Lisez le code, lancez la stack avec Docker Compose et façonnez-la pour votre entrepôt. Contributions bienvenues.',
      ctaPrimary: 'Voir sur GitHub', ctaSecondary: 'Lire la doc',
      footerTag: 'openWCS — Warehouse Control System open source · AGPL-3.0.'
    },

    es: {
      __title: 'openWCS — el Sistema de Control de Almacén de código abierto que controlas de verdad',
      navCap: 'Funciones', navWhy: 'Por qué abierto', navCmp: 'vs. WCS cerrado', navArch: 'Arquitectura',
      btnGitHub: 'Ver en GitHub',
      heroEyebrow: 'Sistema de Control de Almacén de código abierto',
      heroTitle: 'El WCS que<br /><span class="lime">controlas de verdad.</span>',
      heroLead: 'openWCS orquesta tu automatización — transportadores, ASRS, AMR, AutoStore — y el flujo de mercancías en tiempo real, entre tu WMS/ERP y las máquinas. Abierto, independiente del proveedor y modificable por ti.',
      heroPrimary: 'Ver en GitHub', heroSecondary: 'Leer la documentación',
      heroNote: 'Código abierto AGPL-3.0 · autoalojado · sin tarifas de licencia por flujo',
      flowConveyor: 'Transportador',
      capEyebrow: 'Qué hace openWCS',
      capTitle: 'Un WCS que hace el <span class="lime">trabajo real</span> — y te deja cambiarlo.',
      capIntro: 'openWCS es un Sistema de Control de Almacén completo construido en abierto: las mismas capacidades que esperas de un WCS comercial, sin la dependencia. Ejecútalo tú mismo, lee cada línea y adáptalo a tu instalación.',
      c1t: 'Equipos', c1h: 'Independiente del proveedor', c1p: 'Un contrato de dispositivo uniforme con adaptadores para transportadores, ASRS, AMR y AutoStore. Añade un proveedor sin rearquitecturar — su protocolo se queda en el adaptador.',
      c2t: 'Enrutamiento', c2h: 'Enrutamiento que puedes ver', c2p: 'Dibuja la topología de transportadores en un editor visual, enruta las unidades de manipulación por el camino más corto hacia sus destinos, aplica límites de bucle — e incluso aprende el diseño del tráfico en vivo.',
      c3t: 'Procesos', c3h: 'Diseña tus propios flujos', c3p: 'Un diseñador BPMN integrado: modela recepción, expedición e inventario en el navegador, despliega al instante y haz que sus pasos originen trabajo real del WCS.',
      c4t: 'Inventario', c4h: 'Stock en tiempo real, event-sourced', c4p: 'Stock duradero proyectado desde un registro de transacciones append-only, con disponibilidad y reservas por ubicación. Reconstruible, auditable y consultable por ti.',
      c5t: 'Expedición', c5h: 'Asignación, cubicaje y etiquetas', c5p: 'Asignación de ubicaciones de picking con desglose de UM, cubicaje multitamaño en bultos (el mayor primero, trazable por línea), etiquetas de envío por caja y picking por lotes.',
      c6t: 'API anfitrión', c6h: 'Integra tu WMS una vez', c6p: 'Una API anfitrión canónica e independiente del proveedor para pedidos, avisos y confirmaciones. Los adaptadores de SAP y Manhattan traducen a ella; idempotencia y webhooks incluidos.',
      c7t: 'Seguridad', c7h: 'Seguro por diseño', c7p: 'Validación JWT en el gateway, control de acceso por rol y por endpoint, y Keycloak — todo conmutable, para empezar simple y blindar en producción.',
      c8t: 'Arquitectura', c8h: 'Hecho para evolucionar', c8p: 'Microservicios independientes, Postgres con un esquema por servicio, un backbone de eventos Kafka y APIs contract-first — para ampliar o reemplazar cualquier parte.',
      c9t: 'Abierto', c9h: 'Sin dependencia, nunca', c9p: 'Código abierto. Autoaloja en tu propia infraestructura, haz un fork e integra a quien quieras. Irse nunca es un proyecto de reemplazo total.',
      c10t: 'Slotting', c10h: 'Slotting y reabastecimiento', c10p: 'Ubicación configurable para bloques ASRS/AutoStore/AMR — cercanía a la salida, pasillos multiprofundidad de un solo SKU, redundancia de pasillo y equilibrado de llenado — más posiciones de picking fijas con min/máx, reabastecimiento oportunista y directo-a-picking.',
      whyEyebrow: 'El problema',
      whyTitle: 'Tu WCS no debería ser una <span class="lime">caja negra</span>.',
      whyIntro: 'La mayoría de los sistemas de control de almacén los entrega el integrador que instaló tu equipo — software cerrado, atado a su stack y su contrato. La lógica que enruta tus contenedores y asigna tu stock está oculta, y cada cambio pasa por él. Está bien hasta que necesitas moverte rápido.',
      p1t: 'Opaco', p1h: 'Lógica de caja negra', p1p: 'No ves cómo se toman realmente las decisiones de enrutamiento, asignación o cubicaje — así que no puedes confiar en ellas, ajustarlas ni depurarlas.',
      p2t: 'Lento', p2h: 'Cada cambio es un presupuesto', p2p: 'Una nueva ruta de picking o un ajuste de proceso significa una solicitud de cambio, un precio y un puesto en la cola de otro.',
      p3t: 'Atado', p3h: 'Un proveedor, un stack', p3p: 'El WCS está atado al hardware y los protocolos preferidos del integrador. Añadir una nueva familia de dispositivos significa volver a él.',
      p4t: 'Rehén', p4h: 'Tus datos no son tuyos', p4p: 'El historial operativo y el estado en vivo viven dentro de un sistema que no puedes consultar ni exportar en tus términos.',
      p5t: 'Riesgo', p5h: 'Su hoja de ruta, no la tuya', p5p: 'Sus prioridades, sus precios, su ventana de soporte. Cambiar más tarde significa un proyecto de reemplazo total.',
      p6t: 'Coste', p6h: 'Pagar para mantener las luces encendidas', p6p: 'Licencias, contratos de soporte y tarifas por cambios se acumulan — por software que nunca poseerás ni verás del todo.',
      cmpEyebrow: 'openWCS vs. un WCS de integrador cerrado',
      cmpTitle: 'Misma sala de control. <span class="lime">Reglas distintas.</span>',
      cmpFeat: 'Capacidad', cmpThem: 'WCS de integrador típico', cmpUs: 'openWCS',
      r1f: 'Código fuente y propiedad', r1d: 'Binario cerrado que licencias', r1u: 'Código abierto que ejecutas y posees',
      r2f: 'Cambiar un proceso o ruta', r2d: 'Solicitud de cambio de pago, semanas de plazo', r2u: 'Dibújalo en el editor BPMN/topología y despliega tú mismo',
      r3f: 'Proveedores de equipos', r3d: 'Atado al hardware preferido del integrador', r3u: 'Adaptadores independientes sobre un contrato de dispositivo uniforme',
      r4f: 'Lógica de enrutamiento', r4d: 'Reglas opacas de PLC / caja negra', r4u: 'Visual, camino más corto, editable — y aprendible del tráfico',
      r5f: 'Integración WMS/ERP', r5d: 'Interfaz a medida, rehecha por integrador', r5u: 'Una API anfitrión canónica; los adaptadores SAP/Manhattan traducen',
      r6f: 'Tus datos operativos', r6d: 'Atrapados dentro del sistema del proveedor', r6u: 'Registro event-sourced + Postgres que alojas y consultas',
      r7f: 'Cambio / salida', r7d: 'Proyecto de reemplazo total', r7u: 'Sin dependencia de licencia — fork, autoaloja, amplía',
      r8f: 'Modelo de coste', r8d: 'Licencia + soporte + tarifas por cambio', r8u: 'Núcleo gratis; paga solo hardware, operación y los socios que elijas',
      r9f: 'Slotting y reabastecimiento', r9d: 'Reglas fijas del proveedor, opacas y difíciles de ajustar', r9u: 'Scoring de ubicación configurable + reabastecimiento min/máx legible y modificable',
      archEyebrow: 'Bajo el capó', archTitle: 'Transparente por construcción.',
      archIntro: 'Una arquitectura de microservicios limpia que puedes leer de extremo a extremo: cada servicio posee sus datos, los movimientos son eventos en un registro append-only y el equipo vive detrás de adaptadores.',
      a1t: 'Java 21 · Spring Boot', a1h: 'Servicios de dominio', a1p: 'master-data, inventory, orders, allocation, slotting, estaciones goods-to-person, flow-orchestrator, txlog, IAM — cada uno desplegable de forma independiente.',
      a2t: 'Go', a2h: 'Adaptadores de equipo', a2p: 'Controladores ligeros que hablan el protocolo de cada proveedor y el contrato de dispositivo uniforme.',
      a3t: 'Postgres · Kafka', a3h: 'Backbone de eventos', a3p: 'Almacenes con un esquema por servicio y un registro de eventos append-only — auditable, reproducible, reconstruible.',
      ctaEyebrow: 'Construido en abierto', ctaTitle: 'Toma el control de tu sistema de control.',
      ctaP: 'Lee el código, ejecuta el stack con Docker Compose y modélalo para tu almacén. Contribuciones bienvenidas.',
      ctaPrimary: 'Ver en GitHub', ctaSecondary: 'Leer la documentación',
      footerTag: 'openWCS — Sistema de Control de Almacén de código abierto · AGPL-3.0.'
    }
  };

  function detect() {
    try {
      var saved = localStorage.getItem('owcs-lang');
      if (saved && SUPPORTED.indexOf(saved) !== -1) return saved;
    } catch (e) { /* localStorage may be blocked */ }
    var langs = navigator.languages || [navigator.language || navigator.userLanguage || 'en'];
    for (var i = 0; i < langs.length; i++) {
      var two = (langs[i] || '').slice(0, 2).toLowerCase();
      if (SUPPORTED.indexOf(two) !== -1) return two;
    }
    return 'en';
  }

  function apply(lang, persist) {
    var dict = I18N[lang] || I18N.en;
    document.documentElement.lang = lang;
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-i18n');
      var val = dict[key] != null ? dict[key] : I18N.en[key];
      if (val != null) nodes[i].innerHTML = val;
    }
    if (dict.__title) document.title = dict.__title;
    var code = document.getElementById('lang-code');
    if (code) code.textContent = lang.toUpperCase();
    var items = document.querySelectorAll('#lang-menu [data-lang]');
    for (var j = 0; j < items.length; j++) {
      items[j].setAttribute('aria-selected', items[j].getAttribute('data-lang') === lang ? 'true' : 'false');
    }
    if (persist) { try { localStorage.setItem('owcs-lang', lang); } catch (e) {} }
  }

  function reveal() {
    var els = document.querySelectorAll('[data-reveal]');
    if (!('IntersectionObserver' in window) ||
        (window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches)) {
      for (var i = 0; i < els.length; i++) els[i].classList.add('in');
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
    for (var j = 0; j < els.length; j++) io.observe(els[j]);
  }

  function initLangMenu() {
    var root = document.getElementById('lang');
    var toggle = document.getElementById('lang-toggle');
    var menu = document.getElementById('lang-menu');
    if (!root || !toggle || !menu) return;

    function open(state) {
      root.setAttribute('data-open', state ? 'true' : 'false');
      toggle.setAttribute('aria-expanded', state ? 'true' : 'false');
    }
    toggle.addEventListener('click', function (e) {
      e.stopPropagation();
      open(root.getAttribute('data-open') !== 'true');
    });
    menu.addEventListener('click', function (e) {
      var li = e.target.closest('[data-lang]');
      if (!li) return;
      apply(li.getAttribute('data-lang'), true);
      open(false);
    });
    document.addEventListener('click', function () { open(false); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') open(false); });
  }

  function init() {
    apply(detect(), false);
    initLangMenu();
    reveal();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
