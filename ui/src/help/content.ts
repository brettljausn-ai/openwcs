// In-app help content, keyed by screen key (ui/src/auth/screens.ts). The HelpButton in the app
// top bar shows the entry for the current screen.
//
// Every entry follows the same task-first template so a floor operator can act on it:
//   summary                      who uses the screen and the job it does (1-2 sentences)
//   "What you do here"           the core tasks, in real-life order, each with a one-line why
//   "On the floor"               2-3 worked examples with realistic demo identifiers
//   "If something goes wrong"    if/then troubleshooting, most common cases first
//   tips                         short, genuinely supplemental extras
//
// Bodies use \n line breaks (rendered via white-space: pre-line). Keep each entry in sync when a
// screen changes. Screens with no entry simply show no help button. House style: no em dashes.
export interface HelpSection {
  heading: string
  body: string
}

export interface ScreenHelp {
  summary: string
  sections: HelpSection[]
  tips?: string[]
}

export const HELP: Record<string, ScreenHelp> = {
  "hardware-twin": {
    "summary": "The Hardware visualisation screen is the live 3D digital twin of your automation: every conveyor, ASRS and workplace from the topology, coloured by what it is doing right now, with totes moving along the scans the floor actually reported. Use it to see where things are and why they move.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Watch the floor: equipment pulses amber while it works and turns red on a fault, so you spot trouble at a glance.\nFollow a tote: totes glide between the points their scans reported; nothing on this screen is invented.\nInvestigate: click any machine to see its recent device tasks, or click a tote to see its full transport trace.\nDeclutter: use the level selector for multi-floor sites and the Labels toggle to hide names.\nThis screen is read-only: watching never changes anything on the floor."
      },
      {
        "heading": "On the floor",
        "body": "You send tote DEMO-HU-014 from the ASRS to station PP1: it appears at the ASRS outfeed stub, rides BIN_CONVEYOR-1 scan by scan, and queues at PP1; the stats bar counts it as in transit, then queued.\nA sorter keeps recirculating a tote: the tote loops with a small recirculation animation and the stats bar's recirculation counter climbs; click the tote to see each divert decision in its trace.\nStored totes show inside the ASRS rack at their exact cells, so you can watch aisles fill as put-away runs."
      },
      {
        "heading": "If something goes wrong",
        "body": "A tote sits still on the conveyor: it may be waiting at a divert with no default direction, or waiting for slotting to assign a storage slot; click it and check the last trace entry.\nEquipment shows red: it reported a failed device task; click it to read the task and reason, then check the Transport screen for the same correlation.\nNothing moves at all: check that the hardware emulator is ON (Settings, Hardware emulator) on a demo system, and that the topology has been saved and projected.\nA tote you expect is missing: it only appears while it has device tasks; totes resting in storage show inside the rack, not on the belts."
      }
    ],
    "tips": [
      "The twin polls every few seconds; toggle auto-refresh off if you want to freeze the picture while you investigate.",
      "Tote motion between scan points is approximated; positions are exact at every scanned point."
    ]
  },
  "system-info": {
    "summary": "System info is the admin health board: version, build, health and logs for every service and adapter in one table. Use it to confirm a deploy landed and to chase faults without leaving the browser.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Check health: unhealthy units sort to the top with a red pill; the table auto-refreshes every 10 seconds.\nConfirm a deploy: compare each service's version and build time after the demo box redeploys.\nRead logs: click Logs on any row for the latest lines, filter them, or open the full-page view with a day picker (14 days are kept)."
      },
      {
        "heading": "On the floor",
        "body": "A store-back did not happen: open the slotting service's logs and look for a WARN line such as \"request rejected (400): no storage profile / block for sku ...\"; the reason is spelled out in the line.\nAfter a merge to main, the demo box redeploys within a couple of minutes: watch the build time column tick over to confirm the new version is live.\nA screen shows a reconnecting banner: check the gateway row first; if it is healthy, check the service that owns the failing screen."
      },
      {
        "heading": "If something goes wrong",
        "body": "A service shows unhealthy: read its last log lines first; most failures name their cause in the final WARN or ERROR.\nLogs are empty for a day: the service may have restarted and written under the next day's file; check the day picker.\nEverything is red: the gateway itself is likely down; the services may be fine behind it."
      }
    ],
    "tips": [
      "Log files rotate daily with 14-day retention; export anything you need to keep longer.",
      "The log viewer's filter counts matching lines, which is a quick way to count occurrences of an error."
    ]
  },
  "dashboard": {
    "summary": "Your home screen. It shows a tile for every area your role lets you use; pick one to start work.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Get to your work area, so you start in the right place.\n1. Read the tile descriptions to confirm the area.\n2. Click the tile, or focus it and press Enter.\n\nCheck your warehouse, so every screen shows the right site.\n1. Look at the warehouse switcher in the top bar.\n2. Change it before opening a tile if it shows the wrong site."
      },
      {
        "heading": "On the floor",
        "body": "You start the shift on counting duty. Open the Stock counting tile under Operations. Today's count tasks for your warehouse load straight away.\n\nYou run station PP1 today. Open GTP workplaces under Operations, find the PP1 card and open it. The console claims the station for you.\n\nA colleague sees a section you do not. Roles decide which tiles appear, so two people can see different dashboards. That is normal."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a tile you expect is missing: your role does not include it. Ask an admin to grant access under Access control.\n\nIf a tile opens an empty screen: the wrong warehouse is probably selected. Switch it in the top bar and the data loads.\n\nIf a whole section is missing: you have no access to any screen in it, so it is hidden."
      }
    ],
    "tips": [
      "Clicking a tile only navigates. Nothing starts or changes until you act inside the area.",
      "You can open a tile from the keyboard: focus it and press Enter."
    ]
  },
  "inbound": {
    "summary": "Receiving teams use this screen to book arriving deliveries against the inbound orders the host system sends. Posting a receipt turns arrived goods into usable stock.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Receive a delivery, so the goods become stock.\n1. Check the top-bar switcher shows your warehouse.\n2. Find the order: search the reference or filter by status.\n3. Open it and click Receive on the line you are booking in.\n4. Enter the quantity you actually count, not the expected number.\n5. Pick the receiving location and click Post receipt. The stock is booked there.\n\nTrack progress, so you know when an order is complete.\n1. Watch Expected against Received on the order and its lines.\n2. A line flips to Received once its received total meets the expected total.\n3. Click Refresh to pull the latest figures."
      },
      {
        "heading": "On the floor",
        "body": "A truck delivers 24 units of DEMO-SKU-027 (Star Wars Lightsaber model kit). Open the order, click Receive on the line. The quantity field already shows 24, the remaining amount. Choose RCV-DOCK-01 and post. The line flips to Received and the stock appears at RCV-DOCK-01.\n\nOnly 38 of 50 expected boxes arrive. Post 38 now. The line stays Open showing Received 38, and you post the rest when the back order arrives.\n\nYou count 52 boxes, two more than expected. Post 52. Over-receiving is allowed; book what is physically there so stock stays true."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the order is not in the list: check the warehouse switcher, then the status filter. If it is still missing, the host system has not sent it yet.\n\nIf there is no Receive button: the order is Cancelled or Shipped, so it is closed. Receipts only post against open orders.\n\nIf you posted a wrong quantity: a posted receipt is booked stock. Correct it with a stock count or adjustment, then carry on.\n\nIf you wonder where the goods should be stored: you do not choose that here. Put-away and slotting decide where stock goes next."
      }
    ],
    "tips": [
      "The quantity field defaults to the amount still expected, so a full line is usually one click.",
      "You can receive a line in several partial receipts; it stays Open until the expected total is met.",
      "Inbound orders come from your host system; you receive against them but cannot create or edit them."
    ]
  },
  "outbound": {
    "summary": "Move customer orders through release, allocation, cubing and dispatch. The host system creates the orders; you fulfil them here.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Release new orders, so stock can be reserved for them.\n1. Find the order: search the reference or filter by status.\n2. Open it and click Release.\n\nAllocate and cube, so picks and cartons are planned.\n1. Open a Released order.\n2. Click Allocate & cube. Stock is reserved at pick locations and items are packed into cartons (cubing), each with its own label.\n\nDispatch, so the order ships.\n1. Open a fully allocated order.\n2. Click Dispatch. The order is marked Shipped and the picked quantities are posted.\n\nShort allocate a starved order (supervisors), so the customer gets what exists instead of waiting.\n1. Open a Not fulfillable order and click Short allocate & release.\n2. Check the dialog: each short line shows ordered, allocatable and shortfall.\n3. Confirm. The available quantity is picked and the order ships short; the host is told the shipped quantities.\n\nOnly the button for the order's next valid step is shown, so follow the buttons."
      },
      {
        "heading": "On the floor",
        "body": "An order for 10 units of DEMO-SKU-027 (Star Wars Lightsaber model kit) is Released. Click Allocate & cube. The Allocation section shows the pick locations, for example Shuttle Aisle 1-A01-L02-P05-R, and the Cartons section shows the planned shippers.\n\nAn order line is short: 10 wanted, only 4 free. The order shows Partially allocated and the line is marked Short. Receive or replenish the SKU, then run Allocate & cube again.\n\nA priority 1 order must ship by 16:00 (the Dispatch by column). Work it first: Release, Allocate & cube, then Dispatch once every line is allocated.\n\nA customer ordered 50 of DEMO-SKU-027 but only 30 are in pick stock and the truck leaves at 16:00. Open the Not fulfillable order, click Short allocate & release, confirm the dialog, and 30 are picked while 20 ship short; the order shows Partially allocated with the line marked Short."
      },
      {
        "heading": "If something goes wrong",
        "body": "If an order shows Not fulfillable or Partially allocated: there is not enough free stock. Check the SKU on the Stock overview, fix the shortage, then re-run Allocate & cube.\n\nIf stock exists but will not allocate: check where it sits. Stock at location UNKNOWN (the holding place for totes the system cannot place) is never allocatable. Those totes must be found and scanned back into a real location first.\n\nIf the button you expect is missing: each order only offers its next valid step. A Created order offers Release, not Dispatch.\n\nIf a line shows zero Allocatable in the short-release dialog: that line ships nothing at all. If the customer needs it, cancel out and wait for stock instead of confirming.\n\nIf an order must be stopped: Cancel works on any order not yet shipped and frees its reservations."
      }
    ],
    "tips": [
      "Lower Priority numbers are worked first; watch Dispatch by for time-critical orders.",
      "After acting, the open order refreshes itself; use Refresh on the list to catch changes made elsewhere.",
      "Outbound orders are owned by the host system; you progress them here but cannot create or edit them."
    ]
  },
  "counting": {
    "summary": "Capture cycle counts, reconcile them against the system and keep stock figures honest. Tasks come from schedules, ad-hoc requests (Mark for counting on the Stock overview) and recounts.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Capture a count, so the system learns what is really on the shelf.\n1. Click Capture on an Open or Recount task.\n2. Type the quantity you physically find on each line.\n3. On a Blind count the expected figure stays hidden; count what you see.\n4. Click Submit counts. The task moves to Counted.\n\nReconcile, so differences become corrections.\n1. Click Reconcile on a Counted task.\n2. Lines within the task's tolerance post a stock adjustment automatically.\n3. Lines outside tolerance are not adjusted; they spawn a Recount task instead.\n\nSchedule recurring counts, so counting happens without prompting.\n1. Click New schedule; pick a scope, count type, cadence and tolerance.\n2. Click Run ABC sweep to create tasks for due schedules immediately."
      },
      {
        "heading": "On the floor",
        "body": "A blind task covers Shuttle Aisle 1-A01-L02-P05-R. You find 47 units of DEMO-SKU-027 in the tote. Type 47 and submit. On reconcile the system compares your 47 with its own figure and posts the difference as an adjustment if it is within tolerance.\n\nAn ASRS count tote is sent to station PP1 for an at-station count. The task's Routing column shows ROUTED. The operator at PP1 counts it blind, and the result returns to this task, which moves to Counted for you to reconcile.\n\nYour count differs by 6 on a task with tolerance 2. Reconcile adjusts nothing; it creates a Recount task. Count that location again to confirm before stock changes."
      },
      {
        "heading": "If something goes wrong",
        "body": "If Routing shows FAILED on a count task: the count tote could not be routed to a station; the reason is shown beneath the badge. The system retries every minute. If it stays FAILED, check the conveyor and the station (Transport screen).\n\nIf Submit counts is disabled: enter at least one quantity. Only the lines you fill in are recorded.\n\nIf the Expected column is missing: it is a Blind count. That is intended; count what you physically see.\n\nIf a Recount task appears after reconcile: a line was outside tolerance. Nothing was adjusted yet; the recount decides.\n\nIf no tasks are listed: check the warehouse switcher and the status filter, then press Load."
      }
    ],
    "tips": [
      "Prefer Blind counts for unbiased results; Variance counts show the expected figure and suit supervised recounts.",
      "On a schedule, a blank Tolerance means an exact match is required.",
      "Run ABC sweep pulls due scheduled counts into the list right away instead of waiting for the cadence."
    ]
  },
  "gtp-ops": {
    "summary": "The operator console for a goods-to-person station: totes come to you on the conveyor, the screen shows what to pick, put or count, and you confirm each step.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Open your station, so totes can be worked.\n1. Check the warehouse in the top bar; the workplace cards load.\n2. Open your station's card, for example PP1. Use Take over & open if a previous session is still active.\n3. Tick Remember this workstation on this device if you always run it here.\n\nWork arriving totes (PICKING), so orders get filled.\n1. Wait: an arrived tote presents itself and the put list lights up.\n2. Each card shows the order, its put-light and the big quantity to place.\n3. Place the units and press Confirm put.\n4. When all puts are done, press Finish & close cycle. The tote leaves and the next one presents.\n\nCount a tote (Counting mode), so stock stays honest.\n1. The panel shows the tote barcode, the SKU and a picture, never an expected quantity.\n2. Count every unit in the tote and enter the number.\n3. If the panel asks again, count again from scratch.\n\nLeave cleanly, so the next operator can start.\n1. Press Release & exit. The workplace card returns to Free."
      },
      {
        "heading": "On the floor",
        "body": "Tote DEMO-HU-014 arrives at PP1 with 12 units of DEMO-SKU-027 (Star Wars Lightsaber model kit). Three put cards light up: 5, 4 and 3 units for three orders. Place and confirm each; the header counts your puts, then you finish the cycle.\n\nA put asks for 5 units but only 2 usable ones are left in the tote. Type 2 in the short field and press Short. The button only accepts a number above zero and below the asked quantity; the shortfall is recorded against the order.\n\nA count tote arrives while you are in PICKING. The panel says the tote needs Counting mode; press the offered switch button. Count the units blind and submit: ACCEPTED or ADJUSTED moves on to the next tote, RECOUNT clears the field so you count once more."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the screen says Waiting for totes: nothing is inbound for your station yet. Open the Queue drawer on the right edge to check; if totes should be coming, the conveyor or routing may be stopped (see Transport).\n\nIf a tote arrives dirty or damaged: open the Exceptions drawer on the right edge. Mark tote as dirty sends it to maintenance and advances. Mark product as broken posts a damage adjustment for the units you enter; the tote stays so you keep working.\n\nIf the panel shows 'no matching demand': the presented stock fits no open order. Press Mark tote done & advance to send the tote away.\n\nIf you must stop taking new totes: press Deactivate (drain). The station finishes its queued totes but accepts no new ones; Activate resumes.\n\nIf you see Session taken over: the station was opened in another window and unconfirmed work was not saved. Use Back to workplaces and re-open it if it is yours."
      }
    ],
    "tips": [
      "Only one operator can run a workplace at a time; confirm puts as you go so nothing is lost on a takeover.",
      "The console hides the sidebar while open to give you a focus screen; it returns when you exit.",
      "Workplaces themselves are set up by an admin under Configuration, GTP workplaces."
    ]
  },
  "transport": {
    "summary": "A live wallboard of every movement task the system sends to the equipment (conveyors, ASRS, AMRs, AutoStore). Watch tasks progress, spot failures and trace a tote's journey.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Watch the flow, so problems surface early.\n1. Check the warehouse switcher; the board refreshes itself every few seconds.\n2. Read the tiles: Active (in flight), Requested (queued), Dispatched (running), Completed, Failed.\n\nInvestigate a movement, so you know what happened.\n1. Filter Status to Failed, or search by tote, equipment, command or correlation.\n2. Click a row: a dialog shows the task's full detail and the tote's transport trace, step by step.\n3. Turn Auto-refresh off while you read, and back on afterwards."
      },
      {
        "heading": "On the floor",
        "body": "The Failed tile jumps from 0 to 3. Filter Status to Failed. The Detail column carries the reason on each task, for example a conveyor segment that never confirmed.\n\nA tote has not reached PP1 for ten minutes. Search its code, DEMO-HU-014, and click the row: the trace shows every step it completed and where it stopped.\n\nYou want every movement belonging to one operation. Related tasks share a Correlation value; search for it to see the whole chain in order."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a tote sits at a divert and nothing moves: that divert has no route for it and no default direction, so it stops and waits. An engineer sets the divert's default direction in the Automation topology.\n\nIf many tasks stay Requested: the equipment is not picking work up. Check the device and its adapter endpoint (Engineering, Equipment).\n\nIf a task is Failed: read Detail for the cause and fix the physical issue; the system retries or re-issues movements where it can.\n\nIf you look for a travelling tote's stock: while a tote travels, its stock is booked to conveyor locations. If the system loses track of it, the stock books to UNKNOWN and stays unallocatable until the tote is found and scanned again."
      }
    ],
    "tips": [
      "This view is read-only: you cannot create or cancel transports here.",
      "Hover shortened Task, Equipment or Correlation values to see the full identifier.",
      "Sort by Created to see the newest tasks first."
    ]
  },
  "stock-transactions": {
    "summary": "The read-only movement log: every stock-affecting event (receipts, put-aways, moves, picks, adjustments, status changes) in newest-first order. Use it to trace where stock came from and where it went.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Trace a movement, so you can answer 'where did it go?'.\n1. Check the warehouse switcher; the log is scoped to it.\n2. Filter by SKU, location, event type or a time range.\n3. Click a row to expand the full event record.\n\nConfirm work landed, so you trust the figures.\n1. After a receipt, pick or adjustment, press Refresh.\n2. Find the event and check its quantity and locations.\n\nNothing here can be edited or deleted; the log is a permanent record."
      },
      {
        "heading": "On the floor",
        "body": "The Stock overview shows tote DEMO-HU-014 at location UNKNOWN. Filter Location to UNKNOWN and follow the events backwards: the moves show the tote travelling conveyor locations, then a final move to UNKNOWN where the system lost it. Now you know where it was last seen and can go look there.\n\nYou posted a receipt of 24 DEMO-SKU-027 at RCV-DOCK-01 and want proof. Filter Type to Goods received and SKU to DEMO-SKU-027: the event shows quantity +24 at RCV-DOCK-01 with you as Actor.\n\nA count adjusted stock and a colleague asks why. Filter Type to Stock adjusted; the event's Ref ties it to the count task that caused it."
      },
      {
        "heading": "If something goes wrong",
        "body": "If you cannot find an old movement: the list shows recent transactions first. Narrow with a date range and filters.\n\nIf an expected event is missing: give it a moment and press Refresh; events appear as they are recorded.\n\nIf quantities confuse you: a green positive Qty change means stock arrived at the To location, a red negative one means it left the From location.\n\nIf the screen and the Stock overview disagree: they cannot; the overview is calculated from exactly these events. Re-check your filters."
      }
    ],
    "tips": [
      "Matching Ref values group the events of one order or operation.",
      "Expanding a row shows the raw event including correlation ids, the fastest way to tie related events together."
    ]
  },
  "stock-overview": {
    "summary": "A read-only snapshot of what is in stock right now, broken down by handling unit (the tote, carton or pallet stock sits in): where it is, how much is on hand and how much is still free to promise.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Find stock, so you know where things are.\n1. Check the warehouse switcher.\n2. Search by SKU, handling-unit code or location.\n3. Read Qty in stock (on hand) against Qty available (on hand minus reservations).\n\nRequest a check, so doubtful figures get counted.\n1. Find the row in doubt.\n2. Click Mark for counting. A blind count task for that location and SKU is created on the Stock counting screen."
      },
      {
        "heading": "On the floor",
        "body": "A picker asks where DEMO-SKU-027 (Star Wars Lightsaber model kit) lives. Search the SKU code: rows show each tote and location holding it, for example DEMO-HU-014 at Shuttle Aisle 1-A01-L02-P05-R with 47 in stock.\n\nA row shows 47 in stock but only 35 available. The difference of 12 is reserved for orders and cannot be promised again.\n\nA tote shows location UNKNOWN. The system could not place it; that stock is never allocated to orders. Trace its last movements on Stock transactions, find the tote physically, and get it scanned back into a real location. If the figure itself is in doubt, click Mark for counting."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the list is empty: the selected warehouse holds no stock or the wrong warehouse is active.\n\nIf available is lower than you expect: reservations from allocated orders reduce it. Check the order allocations, not the shelf.\n\nIf a number is simply wrong: this screen never edits stock. Click Mark for counting and let the count correct it.\n\nIf a dash shows in the HU column: that stock is not held on a handling unit; a dash in Area means the location has no storage block."
      }
    ],
    "tips": [
      "Sort by Qty available to find free stock for a SKU fast.",
      "Figures come from the same events as the Stock transactions log, so the two always agree."
    ]
  },
  "handling-units": {
    "summary": "The registry of physical containers (totes, cartons, pallets) in the warehouse. Each handling unit has a scannable code, a type, a current location and a status.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Register a new container, so the system can track it.\n1. Check the warehouse switcher; units belong to one warehouse.\n2. Click + Register handling unit.\n3. Enter the Code exactly as printed on the physical label; scans must match it.\n4. Pick the Type, the current Location and a Status, then save.\n\nKeep the registry true, so scans resolve correctly.\n1. Use Edit on a row to correct type, location or status.\n2. Retire a unit by setting Status to RETIRED; there is no delete."
      },
      {
        "heading": "On the floor",
        "body": "A batch of new totes arrives. Register each one: code DEMO-HU-201 as printed on its label, type Tote, location where it sits, status EMPTY. From now on every scan of that barcode resolves to this unit.\n\nA tote's label is scuffed and scanners misread it. Print a fresh label with the same code; the registry record does not change.\n\nA tote is cracked. Edit it and set Status to RETIRED. Its history stays intact, but it is out of service."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a unit shows location UNKNOWN: the system could not place it, and its stock is not allocatable. Find the tote physically and have it scanned at a known point so it books back to a real location.\n\nIf the type you need is missing from the dropdown: types live under Master data, Handling unit types. Ask an admin to add it first.\n\nIf a unit appears in the wrong warehouse: units are scoped to one warehouse; switch the top-bar warehouse to find it.\n\nIf you want to delete a unit: you cannot. Retire it instead so its history is kept."
      }
    ],
    "tips": [
      "Statuses: ACTIVE (in use), EMPTY (registered, holds nothing), IN_TRANSIT (moving), RETIRED (out of service).",
      "After registering or moving a unit, check the Stock overview to confirm what it holds."
    ]
  },
  "topology": {
    "summary": "Model the warehouse automation in 3D: place conveyors, ASRS and workstations on levels, add function points (scanners, diverts, inducts), then generate the routing graph the live system uses to steer totes.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Lay out the equipment, so the model matches the floor.\n1. Pick the warehouse in the top bar; the layout loads in the 3D layout tab.\n2. Add or select a level, then place equipment from the library (registered under Engineering, Equipment).\n3. Select a piece to edit its code, position, rotation and size; draw conveyor paths section by section.\n\nAdd function points, so routing knows the decision spots.\n1. Select a conveyor and add its points: scanner, divert, induct, discharge and so on.\n2. On every divert, set the Default direction: Straight, Branch, or None (stop). The default is what a tote does when routing does not know it (no plan, unknown tote, scanner no-read). With None the tote stops and waits.\n3. Link each workstation box to its GTP workplace and pick its conveyor points by clicking them in 3D.\n\nGenerate and test the routing, so the model becomes live behaviour.\n1. Press Generate routing: it saves, then rebuilds the node and edge graph from the geometry.\n2. Press Test route and click a start and a target point. A lime path with a ghost tote shows the route, or the reachable nodes light up when there is no path.\n3. Use the Routing graph tab to inspect nodes and edges and to edit loops and controllers."
      },
      {
        "heading": "On the floor",
        "body": "A new divert is fitted on BIN_CONVEYOR-1. Add a divert function point at its offset, set Default direction to Straight so unplanned totes carry on, then Save and Generate routing.\n\nTotes cannot reach PP1. Press Test route and click the induct and the PP1 transfer point: no path, and the lit nodes show connectivity ends at a gap between two conveyors. Move them so they touch, regenerate, retest: the ghost tote now runs the route.\n\nOperations reports totes stopping dead at a divert. Open its function point: Default direction is None (stop), so totes without a route wait there forever. Set Straight or Branch and save."
      },
      {
        "heading": "If something goes wrong",
        "body": "If Generate routing misses a link: physical connections are inferred from geometry. Conveyors must actually touch or overlap; nudge positions and regenerate.\n\nIf Test route finds no path: follow the dimly lit reachable nodes to see exactly where connectivity ends, and fix the layout there.\n\nIf the routing graph tables refuse edits: nodes and edges are a projection of the layout, rebuilt wholesale on Generate routing. Change the 3D layout instead; loops and controllers stay editable.\n\nIf your edits seem ignored on the floor: nothing is live until you press Save, and Reload discards unsaved edits. The Unsaved changes badge warns you."
      }
    ],
    "tips": [
      "The 2D plan view is the same model from above; use it for precise placement.",
      "Discover (Routing graph tab) appends nodes and edges observed in live scan traffic, badged discovered, for review.",
      "Loop limits (Max HUs with HOLD or OVERFLOW) protect the line in real time; the overflow target must be a real node."
    ]
  },
  "processes": {
    "summary": "Design, deploy and run the BPMN workflows that drive the warehouse (goods-in, put-away, order release, counting). For admins and process designers: running a process triggers real work on the floor.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Deploy a process, so it can be run.\n1. Check the warehouse switcher; processes act on the active warehouse.\n2. Draw or adjust the flow on the canvas and name it.\n3. Click Deploy. Each deploy creates a new version in the Definitions panel.\n\nRun an instance, so the flow does real work.\n1. Click a process key in Definitions to select it.\n2. Fill in Variables (JSON) with the inputs the run needs.\n3. Click Start. Service steps dispatch device tasks, plan routes, release orders and assign put-away on their own.\n\nComplete wait steps, so a paused flow continues.\n1. Open the Instance panel and click Refresh tasks.\n2. Click Complete on a task once the real-world step is done."
      },
      {
        "heading": "On the floor",
        "body": "You deploy a put-away flow and start an instance with the warehouse id in Variables. The flow plans a conveyor route and dispatches the device task without further input.\n\nA run sits still. Refresh tasks shows an open wait step, a pick confirmation. The operator finishes the pick, you press Complete, and the flow moves on.\n\nYou paste variables with a missing quote. The run refuses to start until the JSON is valid; fix the text and start again."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the run will not start: the Variables JSON is invalid or the process key is wrong. Click the key in Definitions to fill it in correctly.\n\nIf a run seems stuck: press Refresh tasks; a wait step is usually waiting for a person to press Complete.\n\nIf you deployed a mistake: deploy a corrected version. Old versions are kept; the version number tells them apart.\n\nRemember that instances move real handling units and release real orders, so test changes outside live hours."
      }
    ],
    "tips": [
      "The Instance line shows the run id and whether it is still running or has ended.",
      "Keep Variables minimal and valid; the warehouse id is the most common input."
    ]
  },
  "slotting": {
    "summary": "Decide where incoming stock is stored: fixed pick faces with top-up levels, and block rules for automated storage (ASRS, AutoStore, goods-to-person). The put-away engine follows what you set here.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Set up a pick face, so a SKU always has a fixed manual slot.\n1. Check the warehouse switcher.\n2. Fill in the location, SKU and unit of measure.\n3. Set Min (replenishment trigger) and Max (top-up level), Max above Min.\n4. Turn on direct-to-pick to route incoming stock straight to the face, then Add pick face.\n\nSet up block slotting, so automated storage knows where a SKU belongs.\n1. Pick the SKU and the storage Block.\n2. Set Velocity class (A fast, B medium, C slow), Consolidate, Min aisles and Max aisle %.\n3. Click Add block slotting."
      },
      {
        "heading": "On the floor",
        "body": "DEMO-SKU-027 (Star Wars Lightsaber model kit) sells fast. Give it a pick face with Min 10 and Max 40: replenishment triggers at 10 and tops the face back up to 40.\n\nThe same SKU also lives in the shuttle ASRS. Add a block rule: velocity class A, Min aisles 2, Max aisle 50 %. Put-away now spreads it across at least two aisles, so one aisle outage cannot strand it.\n\nGoods-in reports totes circling the conveyor instead of storing. The SKU has no rule for any block with space: only slotting chooses storage slots, and a tote waits on the conveyor when slotting cannot answer. Add or widen a rule and the totes store."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the Add buttons stay disabled: no warehouse is selected in the top bar.\n\nIf totes wait on the conveyor instead of storing: slotting could not answer (no matching rule, or the block is full). Add a rule, free space, or check the block's density on the ASRS report.\n\nIf replenishment never triggers: check the pick face's Min and Max; Max at or below Min gives replenishment nothing to do.\n\nIf you delete a rule by accident: the X removes it immediately without confirmation. Re-add it; existing stock does not move, only future put-away changes."
      }
    ],
    "tips": [
      "Per-block scoring weights (velocity, consolidation, redundancy, balance) live under Settings, Slotting policy.",
      "Velocity class is a starting hint; the system learns real movement over time, so revisit it periodically."
    ]
  },
  "master-data": {
    "summary": "The reference data the warehouse runs on: warehouses, storage blocks, locations, equipment and label templates, plus the host-owned SKU catalog. For admins and supervisors setting up a site.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Manage a catalog, so the rest of the system has correct reference data.\n1. Pick the warehouse in the top bar; storage blocks, locations and equipment are scoped to it.\n2. Open the catalog you need.\n3. Use + New to add a record and Edit to change one. Required fields are marked; Save enables once they are filled.\n4. Where Delete exists it archives: the record is marked ARCHIVED and kept, so references stay intact."
      },
      {
        "heading": "On the floor",
        "body": "You set up a new aisle: create its locations with codes operators can read at a glance, assign them to the right storage block, and give them coordinates so slotting can place fast movers near the exit.\n\nA SKU's description is wrong. SKUs are host-owned and read-only here; correct it in the host system and let it sync."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a tab says select a warehouse: choose one in the top bar; scoped catalogs cannot load without it.\n\nIf you cannot edit a SKU: that is intended. The host system is the single source of truth for SKUs, units of measure and barcodes.\n\nIf a record must go: archive it. Archiving keeps history and does not break existing references."
      }
    ],
    "tips": [
      "Search boxes apply on Enter; column headers sort.",
      "New records are filed against the warehouse selected in the top bar."
    ]
  },
  "master-data:warehouses": {
    "summary": "The top-level sites everything else hangs off. Storage blocks, locations, equipment, orders and stock are all scoped to a warehouse defined here.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Create a warehouse, so a site exists to configure.\n1. Click New and give it a unique Code, a Name and a Timezone.\n2. Save. The warehouse appears in the top-bar switcher and under Warehouse access.\n\nRetire a site, so it leaves day-to-day use.\n1. Archive it. History stays intact; it disappears from active use."
      },
      {
        "heading": "On the floor",
        "body": "You bring a second site online. Create it with code WH-GRAZ and timezone Europe/Vienna, then map users to it under Administration, Warehouse access, and build its blocks and locations.\n\nA schedule fires at the wrong hour. Check the warehouse Timezone: counting cadences and dispatch deadlines run on it."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a new warehouse does not appear for users: they have no access yet. Grant it under Warehouse access.\n\nIf you want to rename a code: the code is referenced everywhere and is hard to change later; pick codes carefully at creation.\n\nIf data seems missing after switching sites: every operational screen is scoped to the active warehouse in the top bar."
      }
    ],
    "tips": [
      "Archive rather than delete to keep the audit trail."
    ]
  },
  "master-data:skus": {
    "summary": "The SKU catalog with units of measure and barcodes. The host system (WMS/ERP) owns SKUs; here you view and verify them, nothing more.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Look a SKU up, so you can verify its data.\n1. Use the host search or filter the loaded rows by code or description.\n2. Click View to open the detail: units of measure with conversion factors, dimensions and weight, plus barcodes with their symbology."
      },
      {
        "heading": "On the floor",
        "body": "A scanner will not read a product barcode. Open DEMO-SKU-027 (Star Wars Lightsaber model kit) and check the barcode value and symbology against the physical label; a mismatch means the host data or the label is wrong.\n\nCubing keeps choosing oversized cartons for an item. Check the SKU's unit dimensions and weight in the detail; cubing calculates with exactly these figures."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a SKU is wrong: fix it in the host system and let it sync; it is read-only here (the HOST-MANAGED badge marks this).\n\nIf a SKU is missing: the host has not sent it yet; trigger or await the sync.\n\nIf you have no host connected: Settings, Demo mode can seed sample SKUs to explore with."
      }
    ],
    "tips": [
      "Search by partial code or description to find a SKU fast."
    ]
  },
  "master-data:storage-blocks": {
    "summary": "The storage pools the slotting and put-away engines work with: manual pick-face areas and automated systems (ASRS, AutoStore, AMR goods-to-person). Scoped to the warehouse in the top bar.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Define a block, so put-away knows how that area stores stock.\n1. Pick the warehouse, then click New.\n2. Set the Code, the Storage type (manual racking or an automated pool) and the Slotting granularity (per location, or the block as a pool).\n3. List the Allowed HU types; blank means any handling-unit type may enter.\n\nKeep blocks current, so rules apply correctly.\n1. Edit a block to adjust its type or allowed HU types.\n2. Retire one by setting its Status to ARCHIVED in Edit; there is no delete button."
      },
      {
        "heading": "On the floor",
        "body": "You add a shuttle ASRS. Create a block with storage type shuttle ASRS and granularity block: slotting then treats it as one pool and scores locations inside it.\n\nOversized cartons keep being sent to the AutoStore. Set the block's Allowed HU types to the bin type only; put-away stops offering it to anything else."
      },
      {
        "heading": "If something goes wrong",
        "body": "If put-away ignores a block: check its storage type and the slotting rules pointing at it (Engineering, Slotting).\n\nIf a block is full: see the ASRS report's density figures, free stock or add capacity.\n\nIf the per-block scoring needs tuning: weights live under Settings, Slotting policy, not here."
      }
    ],
    "tips": [
      "The GTP flag marks goods-to-person blocks.",
      "Keep Allowed HU types in sync with the handling-unit types catalog."
    ]
  },
  "master-data:locations": {
    "summary": "The addressable places stock can live: pick faces, rack positions, automation cells, staging. Scoped to the warehouse in the top bar and grouped into storage blocks.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Create locations, so stock has addresses.\n1. Pick the warehouse, then add each location with a unique, readable code.\n2. Set its Type and Purpose (storage, pick, pack, receiving, shipping).\n3. Assign the storage Block and fill aisle/side, X/Y/Z position and distance to exit, so slotting and routing can optimise.\n\nMaintain them, so the model stays true.\n1. Edit codes, blocks or coordinates as the floor changes.\n2. Archive locations that no longer exist."
      },
      {
        "heading": "On the floor",
        "body": "You commission a shuttle rack position. Its code, Shuttle Aisle 1-A01-L02-P05-R, encodes aisle, level and position, so anyone can find it on the floor. With coordinates and distance to exit set, velocity slotting puts fast movers like DEMO-SKU-027 near the exit.\n\nReceiving needs a buffer area. Create a location with purpose receiving, for example RCV-DOCK-01, and receipts can be booked straight to it."
      },
      {
        "heading": "If something goes wrong",
        "body": "If slotting ignores a location: it is probably missing its block or coordinates.\n\nIf operators mistype codes: choose short, consistent, scannable codes; they read them all day.\n\nIf a location was built wrong: archive it and create the correct one; archiving keeps the history of stock that lived there."
      }
    ],
    "tips": [
      "Mixed SKUs allowed controls whether different SKUs may share the location."
    ]
  },
  "master-data:equipment": {
    "summary": "The registry of devices the WCS talks to (conveyors and PLCs, ASRS, AutoStore, AMR fleets) and the adapter endpoints used to reach them. Scoped to the warehouse in the top bar.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Register a device, so the system can drive it.\n1. Pick the warehouse, then click New.\n2. Set the Family (CONVEYOR, ASRS, AMR, AUTOSTORE), Vendor and Model.\n3. Enter the Adapter endpoint, the URL where the device adapter listens. Tasks are dispatched through it.\n\nKeep endpoints current, so tasks reach the hardware.\n1. Edit the record whenever an adapter moves.\n2. Registered equipment also appears in the Automation topology's library for placement."
      },
      {
        "heading": "On the floor",
        "body": "You add the bin conveyor BIN_CONVEYOR-1: family CONVEYOR, the vendor and model, and its adapter endpoint. It can now be placed in the 3D topology and receives transport tasks.\n\nAn adapter is redeployed to a new host. Update the endpoint here, or every task for that device fails to arrive."
      },
      {
        "heading": "If something goes wrong",
        "body": "If device tasks never reach the hardware: the adapter endpoint is wrong or the adapter is down. Check System info for adapter health.\n\nIf routing picks the wrong adapter: the Family steers dispatching; keep it accurate.\n\nIf the conveyor network itself needs modelling: that happens in Engineering, Automation topology; this screen is only the device registry."
      }
    ],
    "tips": [
      "Equipment is warehouse-scoped; switch the top-bar warehouse to see another site's devices."
    ]
  },
  "master-data:handling-unit-types": {
    "summary": "The global catalog of container types (totes, trays, cartons, pallets, shippers) with their dimensions, weight limit and capabilities. Cubing, put-away and automation all rely on these envelopes.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Define a type, so the system knows what the container can do.\n1. Click New and name the type.\n2. Enter dimensions (length, width, height in mm) and the weight limit in grams.\n3. Set the capabilities: Nestable (empties stack), Compartments, and whether it may enter Automation and ride the Conveyor."
      },
      {
        "heading": "On the floor",
        "body": "You introduce a half-size tote with two compartments for small parts. Define it with its true envelope and 2 compartments; GTP put lists then address the right compartment.\n\nA pallet type keeps being offered to the AutoStore by mistake. Clear its Automation flag; put-away stops considering automated pools for it."
      },
      {
        "heading": "If something goes wrong",
        "body": "If cubing packs impossible cartons: a type's dimensions or weight limit is wrong; cubing calculates with exactly these figures.\n\nIf a tote is rejected at a storage block: the block's Allowed HU types does not include it. Fix whichever side is wrong.\n\nIf a type is no longer used: edit it and set its status accordingly rather than reusing its name for something different."
      }
    ],
    "tips": [
      "This catalog is global, not per warehouse.",
      "Nestable empties save space on return flows."
    ]
  },
  "master-data:label-templates": {
    "summary": "The dispatch and handling-unit label layouts the system renders (ZPL for printers or PDF), with their fields and barcodes. Cubing uses them when shippers get labels.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Create a template, so labels print correctly.\n1. Click New; set the Code, the physical Size in mm and the printer DPI.\n2. Define the elements: text, data fields and barcodes.\n3. Use data fields rather than fixed text, so each label fills in its own order and shipper data."
      },
      {
        "heading": "On the floor",
        "body": "Your courier requires a 100 x 150 mm label on a 203 dpi printer. Create the template at exactly that size and DPI; a mismatch prints misaligned labels.\n\nDispatch wants the order reference as a barcode. Add a barcode element bound to the order reference field; every shipper label then carries it."
      },
      {
        "heading": "If something goes wrong",
        "body": "If labels print misaligned: the template size or DPI does not match the label stock or printer.\n\nIf scanners cannot read printed barcodes: check the symbology matches what your scanners are configured for.\n\nIf the wrong template prints: which template a dispatch uses is decided by your shipping configuration, not here."
      }
    ],
    "tips": [
      "Element count in the list shows how complete a template is at a glance."
    ]
  },
  "gtp-config": {
    "summary": "Set up goods-to-person stations: each workplace's layout, the activities it supports, its STOCK and ORDER nodes, and how many totes may be en route to it. For admins; operators use the stations on the GTP workplaces screen.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Create a workplace, so operators have a station to run.\n1. Pick the warehouse, click + New workplace.\n2. Set the Code (for example PP1), the destination topology (ORDER_LOCATION or PUT_WALL) and the operating modes it supports.\n\nAdd its nodes, so the station has positions.\n1. Select the workplace row; the Nodes panel opens.\n2. Add at least one STOCK node (where totes present) and one or more ORDER nodes (where items are placed), with put-light ids on ORDER nodes.\n3. Use Position to mirror the physical order operators see.\n\nCap the inbound flow, so the station is not flooded.\n1. Open the in-transit capacity dialog.\n2. Set how many totes may be en route at once, for picking and for other work separately."
      },
      {
        "heading": "On the floor",
        "body": "You commission PP1 as a put wall: code PP1, topology PUT_WALL, modes PICKING and STOCK_COUNT, one STOCK node and eight ORDER nodes with their put-light ids. Operators can open it immediately.\n\nThe operator reports puts lighting in a confusing order. Reorder the ORDER nodes' Position fields to match the physical rack, left to right.\n\nPP1 drowns in arriving totes. Lower its in-transit picking cap; routing stops sending new totes once the cap is reached."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a workplace does not appear for operators: check the warehouse and its Status; only Active workplaces are usable.\n\nIf presenting stock fails at the station: the workplace may lack a STOCK node, or nodes are Inactive.\n\nIf you consider deleting a workplace: deletion removes all its nodes and cannot be undone. Set it Inactive to pause it instead.\n\nIf count totes never arrive at the station: check the station supports the STOCK_COUNT mode and the counting task's Routing column for failures."
      }
    ],
    "tips": [
      "PICKING is always on; add other modes only where that work really happens.",
      "Put-light and order-container fields appear once a node's role is ORDER."
    ]
  },
  "settings": {
    "summary": "The admin console for system policy: slotting weights, cubing shippers, counting schedules, stock rules, plus read-only integration and system health. Changes write to the live system immediately.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Tune put-away per block (Slotting policy), so stock lands well.\n1. Pick the warehouse and a storage block.\n2. Balance the four weights: Velocity, Consolidation, Redundancy, Balance. They are relative to each other.\n3. Set aisle constraints and, if wanted, automatic re-slotting with its off-peak window. Save policy applies immediately.\n\nManage cartons (Cubing), so orders pack into the right shippers.\n1. Maintain the shipper list and the fulfilment configuration.\n\nKeep counting running (Counting), so stock stays audited.\n1. Add schedules with scope, Blind or Variance, cadence and tolerance.\n2. Press Run sweep now to create due count tasks immediately.\n\nSet stock rules (Stock rules), so integrity is enforced.\n1. Toggle single SKU per compartment; it is enforced when totes are filled at decanting.\n\nCheck health (Integrations, System status), so you know the platform is connected.\n1. Read the Reachable or Down badges on the host connection and adapters; these tabs are read-only."
      },
      {
        "heading": "On the floor",
        "body": "Put-away scatters one SKU across the shuttle. Raise the Consolidation weight a step on that block and watch the next put-aways group it; small steps, the weights trade off against each other.\n\nYou add the schedule 'A-class weekly': scope ABC class A, Blind, cadence 7 days, tolerance 2. Run sweep now creates the first tasks; operators see them on Stock counting.\n\nThe host adapter shows Down on Integrations. Order sync will be stalled; involve your platform team. Nothing on that tab is editable."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a save seems to do nothing: changes apply to new decisions only; existing stock does not jump. Watch the next put-aways.\n\nIf re-slotting surprises you with moves: it runs in the off-peak window you set, limited by the shift percentage per run. Disable it if unwanted.\n\nIf a block shows default policy values with a note: no policy exists yet; Save policy creates it.\n\nIf Demo mode or Hardware emulator tempts you on a live site: they seed sample data and simulate hardware. Use them only on demo systems."
      }
    ],
    "tips": [
      "There is no separate publish step and no undo; adjust in small steps.",
      "Velocity and ABC shares shown under slotting are calculated by the system and read-only."
    ]
  },
  "users": {
    "summary": "Create and manage the accounts that sign in to openWCS: passwords, active state, roles and default warehouse. Admin only.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Create an account, so a new person can work.\n1. Click + New user and fill in the username (it cannot be changed later).\n2. Set a password, ticked as Temporary so they choose their own at first sign-in.\n3. Assign at least one role: ADMIN, SUPERVISOR, OPERATOR or VIEWER. Without a role they see almost nothing.\n4. Edit the user to set their Default warehouse; this also grants access to it.\n\nManage the lifecycle, so access stays correct.\n1. Disable an account to block sign-in without losing history.\n2. Delete only when you are sure; it is permanent.\n3. Use Password to reset credentials."
      },
      {
        "heading": "On the floor",
        "body": "A new picker starts Monday. Create the account, set a temporary password, give the OPERATOR role and their site as default warehouse. At first sign-in they pick their own password and land in the right warehouse.\n\nSomeone leaves for three months. Disable the account; everything is preserved for their return.\n\nAn operator cannot see the Transport screen. Check their roles here; if the role is right, the screen's access may be overridden under Access control."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a new account cannot sign in: it needs both a password and at least one role.\n\nIf a username is misspelled: usernames are locked after creation; create a fresh account and delete the wrong one.\n\nIf someone lands in the wrong warehouse: set their Default warehouse in Edit; it takes effect at next sign-in.\n\nIf you hesitate between Disable and Delete: prefer Disable. Delete cannot be undone."
      }
    ],
    "tips": [
      "Give the least access needed: VIEWER for read-only, OPERATOR for floor work, ADMIN only for the few who manage users.",
      "Role and status changes take effect at the person's next action or sign-in."
    ]
  },
  "access-control": {
    "summary": "Decide who can open each screen: per-screen role toggles plus named individual users. Admin only; admins always keep access to everything.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Override a screen's access, so the right people see it.\n1. Find the screen row in its section.\n2. Flip the role toggles (ADMIN, SUPERVISOR, OPERATOR, VIEWER). The first change takes control of the row and replaces its built-in default.\n3. Add named people under Allowed users for exceptions on top of roles.\n4. Click Save changes; nothing applies until then.\n\nReturn to defaults, so custom rules do not pile up.\n1. Click Clear on a row to drop its override; the default badge returns."
      },
      {
        "heading": "On the floor",
        "body": "Viewers should see the Reporting screens. Tick VIEWER on each reporting row and save; the screens appear in their navigation immediately.\n\nOne operator covers transport monitoring this week. Add them under Allowed users on the Transport row instead of opening it to every operator.\n\nAn experiment went too far. Click Clear on the affected rows and save; the built-in defaults apply again."
      },
      {
        "heading": "If something goes wrong",
        "body": "If you fear locking yourself out: you cannot. Admins always retain access, even with ADMIN unticked.\n\nIf changes seem ignored: they only apply after Save changes; check the overridden counter at the top.\n\nIf a row with everything unticked confuses you: an empty override counts as no override, so the screen falls back to its defaults.\n\nIf someone still cannot see a screen: screen access is one gate; their warehouse access and roles also matter."
      }
    ],
    "tips": [
      "Dimmed toggles on default rows just show who can already open the screen.",
      "This governs screen visibility only, nothing about orders or stock."
    ]
  },
  "warehouse-access": {
    "summary": "Map each user to the warehouses they may work in and set their sign-in default. Admin only; this drives the warehouse switcher everyone else sees.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Grant or revoke warehouses, so people work in the right sites.\n1. Search the user; rows page in groups of 20.\n2. Flip the toggle in each warehouse column on or off.\n3. Pick their Default warehouse from the allowed ones; that is where they land at sign-in.\n4. Click Save changes; all edited rows commit together."
      },
      {
        "heading": "On the floor",
        "body": "An operator transfers sites next week. Toggle the new warehouse on, set it as their Default, and save. At next sign-in they land in the new site.\n\nYou revoke a user's current default warehouse. The Default clears automatically; pick a new one before saving so they do not sign in without one.\n\nYou edit ten users across three search pages. The edited badges and the unsaved counter follow you; one Save changes commits everything."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a warehouse is missing from a user's Default list: toggle that warehouse on first; defaults only offer allowed warehouses.\n\nIf the grid is empty: no warehouses exist yet. Create one under Master data, Warehouses.\n\nIf an admin is not listed: that is intended; admins are never warehouse-scoped.\n\nIf edits vanished: you navigated away without Save changes. Watch the unsaved counter."
      }
    ],
    "tips": [
      "Hover a warehouse column header to see the full name behind the short code."
    ]
  },
  "reporting:material-flow": {
    "summary": "How well the conveyor system reads and moves totes: scan quality per scan point and day, scanners needing attention, a 3D traffic heatmap and daily transit times.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Check scan quality, so bad reads are caught early.\n1. Pick the warehouse and a reporting window (7 to 90 days).\n2. Read the stacked bars: good reads, no reads (label seen but unreadable) and unknowns (barcode the system does not know).\n3. The chips summarise total scans, errors and the error rate.\n\nTriage scanners, so failing hardware is fixed before it hurts.\n1. Open the scanners-needing-attention table; flagged rows sit on top.\n2. A scanner is flagged above 2 % error rate or with a rising trend.\n\nRead the flow, so congestion shows itself.\n1. Rotate the 3D heatmap: conveyors are tinted by traffic over the window.\n2. Check daily p50/p95 transit times below it."
      },
      {
        "heading": "On the floor",
        "body": "The scanner on BIN_CONVEYOR-1 shows a rising error-rate trend, now 3 %. Rising no-reads usually mean a dirty lens, misalignment or failing labels upstream. Worse: every no-read tote takes the divert's default direction instead of its planned route. Clean and align the scanner before chutes fill up.\n\nThe heatmap shows one conveyor glowing far hotter than the rest. That is your bottleneck candidate; compare it with transit times.\n\np95 transit time rises while p50 stays flat. A minority of totes is held up: typically recirculation or a congested loop on part of the system."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a scanner's rate looks wild but it scans little: small samples are noisy. Judge it over a 30 or 90 day window.\n\nIf unknown barcodes climb: totes unknown to the system are circulating; they route by divert defaults only. Check where they enter the loop.\n\nIf the report is empty: history accumulates from deployment day; a fresh system starts blank.\n\nIf traffic seems missing from the heatmap: transports that cannot be mapped to a placed conveyor are reported under the heatmap rather than silently dropped."
      }
    ],
    "tips": [
      "Use 90 days to see weekly patterns, 7 days to inspect this week's anomalies.",
      "Toggle Labels in the heatmap to see equipment codes and scan-point markers."
    ]
  },
  "reporting:asrs": {
    "summary": "The automated storage system at a glance: storage density with history and a 14-day forecast, a 3D heatmap of where movements happen in the rack, and movements per device.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Watch density, so you act before the rack is full.\n1. Pick the warehouse; the report covers the last 90 days.\n2. Read the chips for the latest day and the chart's dashed 14-day forecast.\n3. The block table breaks the latest day down per storage block.\n\nRead the movement heatmap, so imbalances show.\n1. Hot cells mark where stores and retrieves concentrate; a cold band can reveal unbalanced slotting.\n\nCompare devices, so hardware trouble stands out.\n1. Check completed and failed tasks per shuttle, crane and lift in the stacked bars and table."
      },
      {
        "heading": "On the floor",
        "body": "Density is 86 % and the forecast crosses 95 % within two weeks. Free stock, archive dead items or add capacity now, before put-away stalls and totes start waiting on the conveyor.\n\nFailures cluster on one shuttle while the others run clean. That points at hardware on that device, not software; raise it with maintenance.\n\nThe heatmap shows one aisle doing most of the work. Check the slotting rules' Min aisles and Max aisle % so the load spreads."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the 3D heatmap looks sparse: movements on locations without rack-cell coordinates cannot be painted; the screen reports how many were skipped.\n\nIf a block's density looks frozen: a block that stopped reporting shows its last known day.\n\nIf the forecast looks jagged: it is weekday-seasonal, each forecast day averaging the same weekday over trailing weeks. That is intended."
      }
    ],
    "tips": [
      "Use the Labels toggle to identify equipment and scan points around the rack."
    ]
  },
  "reporting:stock": {
    "summary": "Current stock per SKU in single units, split between available (free to allocate), allocated (reserved for orders) and unavailable (blocked or damaged). A now-snapshot, not a history.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Read the warehouse position, so you know what can be promised.\n1. Pick the warehouse; the chips total SKUs, units and the three-way split.\n2. The stacked bars show the biggest SKUs by total units.\n\nDig into a SKU, so issues get owners.\n1. Search the table by SKU code or description.\n2. Sort by any column to rank the problem."
      },
      {
        "heading": "On the floor",
        "body": "Sort by Unavailable. The top SKU has 60 blocked units: investigate whether they are damaged, expired or simply mis-statused, then clear or write them off.\n\nDEMO-SKU-027 (Star Wars Lightsaber model kit) shows 120 allocated and 8 available. Free stock is nearly gone; expect the next order to come out Partially allocated unless replenishment lands first."
      },
      {
        "heading": "If something goes wrong",
        "body": "If the report disagrees with your gut: it is calculated from the live stock ledger; verify against the Stock overview and the transactions log, which always agree with it.\n\nIf available is zero but stock exists: it is all reserved or unavailable. Stock at UNKNOWN locations is never allocatable; find those totes.\n\nIf a SKU is missing: it holds no stock in this warehouse right now; this is a snapshot, not a history."
      }
    ],
    "tips": [
      "Quantities are in the SKU's base unit of measure."
    ]
  },
  "reporting:inbound": {
    "summary": "What is coming into the warehouse: expected inbound, started and active receiving, daily volumes over the last 90 days, and the hours of day deliveries really arrive.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Read the headline, so you see the backlog.\n1. Pick the warehouse.\n2. Expected inbound counts host orders not yet turned into stock; Started and Active count receiving work in the window.\n\nPlan staffing, so people are there when trucks are.\n1. Read the day map: each cell is an hour of day, coloured by inbound activity across the window.\n2. Outlined cells are the peaks (at least 85 % of the busiest hour)."
      },
      {
        "heading": "On the floor",
        "body": "The day map outlines 06:00 to 09:00 as the peak hours, while your receiving shift starts at 08:00. Trucks queue for two hours every morning; shift the staffing to match when deliveries actually arrive.\n\nThe received line climbs all week while the completed line stays flat. Receiving is falling behind the host's deliveries; add hands before the dock buffer overflows."
      },
      {
        "heading": "If something goes wrong",
        "body": "If Expected inbound stays high while Active is low: orders are waiting for goods that never arrived. Chase the suppliers, not the dock team.\n\nIf days show zero: quiet days render as zero on purpose, so gaps in the operation stay visible.\n\nIf the report is empty: history accumulates from deployment day."
      }
    ],
    "tips": [
      "Compare with the Outbound day map to find hours where both compete for the same staff and equipment."
    ]
  },
  "reporting:outbound": {
    "summary": "What is leaving the warehouse: expected outbound, started and active fulfilment, daily volumes over the last 90 days, and the hours of day the outbound volume really runs.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Read the headline, so you see the backlog.\n1. Pick the warehouse.\n2. Expected outbound counts host orders not yet released to the floor; Started and Active count fulfilment work in the window.\n\nCheck the rhythm, so cut-offs are met.\n1. Read the day map: each cell is an hour of day, coloured by outbound activity.\n2. Outlined cells are the peaks; they normally sit just before carrier cut-offs."
      },
      {
        "heading": "On the floor",
        "body": "The peak hours sit at 17:00 to 18:00 but your carrier collects at 16:00. Orders are released too late to make the truck; move release earlier in the day.\n\nThe received line rises while completed stays flat. That is the earliest backlog warning: tomorrow's dispatch deadlines are already at risk."
      },
      {
        "heading": "If something goes wrong",
        "body": "If Expected outbound piles up: orders are not being released. Check the Outbound orders screen for stuck Created orders.\n\nIf completed work dips on single days: check those days for short stock (Partially allocated orders) or equipment failures on the Transport screen.\n\nIf the report is empty: history accumulates from deployment day."
      }
    ],
    "tips": [
      "Compare with the Inbound day map to spot hours of competing workload."
    ]
  },
  "admin-database": {
    "summary": "A read-only window into the shared PostgreSQL database: browse every service schema and run SELECT queries. A diagnostic tool; nothing here can change data.",
    "sections": [
      {
        "heading": "What you do here",
        "body": "Inspect a table, so you see the raw truth.\n1. Expand a schema in the left tree (master_data, inventory, orders, flow, and so on).\n2. Click a table: a select * limit 100 runs immediately, and its columns and types show under the name.\n\nRun your own query, so you can answer hard questions.\n1. Write a single SELECT (or WITH ... SELECT) and press Run or Ctrl/Cmd+Enter.\n2. Filter, sort and page the result grid; the row count, a truncated badge and the execution time sit above it."
      },
      {
        "heading": "On the floor",
        "body": "A tote's stock looks odd in the UI. Query inventory stock joined to master_data handling units on the HU id and compare the rows with what the screens show.\n\nYou type an UPDATE out of habit. The console rejects it before it reaches the database: only single SELECT statements are accepted, and every query runs in a read-only transaction that is rolled back.\n\nA big query returns exactly 200 rows with a truncated badge. The cap cut it short; add a WHERE or raise the limit (1000 at most)."
      },
      {
        "heading": "If something goes wrong",
        "body": "If a query errors: the banner shows the PostgreSQL message; correct the statement.\n\nIf a statement is refused: anything that is not a single SELECT is rejected with the reason.\n\nIf a query never finishes: queries are cut off after 10 seconds; narrow the scan.\n\nIf you need to change data: use the proper screens or APIs, which enforce business rules and auditing."
      }
    ],
    "tips": [
      "All services share one database, so cross-schema joins work.",
      "Double-quote a column named like a SQL keyword.",
      "Your last query is remembered, so the console reopens where you left off."
    ]
  }
}
