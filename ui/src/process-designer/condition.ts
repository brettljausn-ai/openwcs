// Safe evaluator for the restricted `when` grammar (spec §6). This is deliberately NOT eval/Function:
// it is a hand-written recursive-descent parser + interpreter over a tiny grammar, so a definition
// (data that can come from the server / a non-developer designer) can never execute host code.
//
// Grammar (lowest -> highest precedence):
//   expr   := or
//   or     := and ( "or" and )*
//   and    := not ( "and" not )*
//   not    := "not" not | cmp
//   cmp    := primary ( ("=="|"!="|"<"|"<="|">"|">=") primary )?
//   primary:= "(" expr ")" | literal | ident
//   literal:= number | string ('...' or "...") | true | false | null
//   ident  := data-object variable name, may be dotted (e.g. sku.code)
//
// Operands resolve against the data object. Comparisons coerce sensibly (number vs number,
// boolean vs boolean, string vs string; mixed -> string compare). Unknown variable = undefined.
// Any parse error throws; callers treat a failed `when` as "not matched" (see flow walker).

type Tok =
  | { t: 'num'; v: number }
  | { t: 'str'; v: string }
  | { t: 'bool'; v: boolean }
  | { t: 'null' }
  | { t: 'ident'; v: string }
  | { t: 'op'; v: string }
  | { t: 'lparen' }
  | { t: 'rparen' }
  | { t: 'and' }
  | { t: 'or' }
  | { t: 'not' }

const OPS = ['==', '!=', '<=', '>=', '<', '>']

function tokenize(src: string): Tok[] {
  const toks: Tok[] = []
  let i = 0
  const n = src.length
  while (i < n) {
    const c = src[i]
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r') { i++; continue }
    if (c === '(') { toks.push({ t: 'lparen' }); i++; continue }
    if (c === ')') { toks.push({ t: 'rparen' }); i++; continue }
    // string literal
    if (c === "'" || c === '"') {
      const quote = c
      let j = i + 1
      let s = ''
      while (j < n && src[j] !== quote) {
        if (src[j] === '\\' && j + 1 < n) { s += src[j + 1]; j += 2; continue }
        s += src[j]; j++
      }
      if (j >= n) throw new Error('Unterminated string literal')
      toks.push({ t: 'str', v: s })
      i = j + 1
      continue
    }
    // operator
    const op = OPS.find((o) => src.startsWith(o, i))
    if (op) { toks.push({ t: 'op', v: op }); i += op.length; continue }
    // number
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(src[i + 1] ?? ''))) {
      let j = i
      while (j < n && /[0-9.]/.test(src[j])) j++
      toks.push({ t: 'num', v: Number(src.slice(i, j)) })
      i = j
      continue
    }
    // identifier / keyword
    if (/[A-Za-z_]/.test(c)) {
      let j = i
      while (j < n && /[A-Za-z0-9_.]/.test(src[j])) j++
      const word = src.slice(i, j)
      i = j
      if (word === 'and') toks.push({ t: 'and' })
      else if (word === 'or') toks.push({ t: 'or' })
      else if (word === 'not') toks.push({ t: 'not' })
      else if (word === 'true') toks.push({ t: 'bool', v: true })
      else if (word === 'false') toks.push({ t: 'bool', v: false })
      else if (word === 'null') toks.push({ t: 'null' })
      else toks.push({ t: 'ident', v: word })
      continue
    }
    throw new Error(`Unexpected character "${c}" at ${i}`)
  }
  return toks
}

type Node =
  | { k: 'lit'; v: unknown }
  | { k: 'var'; path: string }
  | { k: 'cmp'; op: string; l: Node; r: Node }
  | { k: 'and'; l: Node; r: Node }
  | { k: 'or'; l: Node; r: Node }
  | { k: 'not'; e: Node }

class Parser {
  private pos = 0
  constructor(private toks: Tok[]) {}

  private peek(): Tok | undefined { return this.toks[this.pos] }
  private next(): Tok | undefined { return this.toks[this.pos++] }

  parse(): Node {
    const e = this.parseOr()
    if (this.pos !== this.toks.length) throw new Error('Trailing tokens in condition')
    return e
  }

  private parseOr(): Node {
    let l = this.parseAnd()
    while (this.peek()?.t === 'or') { this.next(); l = { k: 'or', l, r: this.parseAnd() } }
    return l
  }
  private parseAnd(): Node {
    let l = this.parseNot()
    while (this.peek()?.t === 'and') { this.next(); l = { k: 'and', l, r: this.parseNot() } }
    return l
  }
  private parseNot(): Node {
    if (this.peek()?.t === 'not') { this.next(); return { k: 'not', e: this.parseNot() } }
    return this.parseCmp()
  }
  private parseCmp(): Node {
    const l = this.parsePrimary()
    const p = this.peek()
    if (p?.t === 'op') { this.next(); return { k: 'cmp', op: p.v, l, r: this.parsePrimary() } }
    return l
  }
  private parsePrimary(): Node {
    const tok = this.next()
    if (!tok) throw new Error('Unexpected end of condition')
    switch (tok.t) {
      case 'lparen': {
        const e = this.parseOr()
        if (this.next()?.t !== 'rparen') throw new Error('Expected )')
        return e
      }
      case 'num': return { k: 'lit', v: tok.v }
      case 'str': return { k: 'lit', v: tok.v }
      case 'bool': return { k: 'lit', v: tok.v }
      case 'null': return { k: 'lit', v: null }
      case 'ident': return { k: 'var', path: tok.v }
      default: throw new Error(`Unexpected token in condition`)
    }
  }
}

function resolveVar(path: string, data: Record<string, unknown>): unknown {
  const parts = path.split('.')
  let cur: unknown = data
  for (const p of parts) {
    if (cur == null || typeof cur !== 'object') return undefined
    cur = (cur as Record<string, unknown>)[p]
  }
  return cur
}

function compare(op: string, a: unknown, b: unknown): boolean {
  if (op === '==') return looseEq(a, b)
  if (op === '!=') return !looseEq(a, b)
  // ordered comparisons: numeric when both coerce to numbers, else string
  const an = typeof a === 'number' ? a : Number(a)
  const bn = typeof b === 'number' ? b : Number(b)
  let x: number | string, y: number | string
  if (!Number.isNaN(an) && !Number.isNaN(bn) && a !== '' && b !== '') { x = an; y = bn }
  else { x = String(a); y = String(b) }
  switch (op) {
    case '<': return x < y
    case '<=': return x <= y
    case '>': return x > y
    case '>=': return x >= y
    default: return false
  }
}

function looseEq(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a == null || b == null) return a == null && b == null
  if (typeof a === 'boolean' || typeof b === 'boolean') return Boolean(a) === Boolean(b)
  if (typeof a === 'number' || typeof b === 'number') {
    const an = Number(a), bn = Number(b)
    if (!Number.isNaN(an) && !Number.isNaN(bn)) return an === bn
  }
  return String(a) === String(b)
}

function evalNode(node: Node, data: Record<string, unknown>): unknown {
  switch (node.k) {
    case 'lit': return node.v
    case 'var': return resolveVar(node.path, data)
    case 'not': return !truthy(evalNode(node.e, data))
    case 'and': return truthy(evalNode(node.l, data)) && truthy(evalNode(node.r, data))
    case 'or': return truthy(evalNode(node.l, data)) || truthy(evalNode(node.r, data))
    case 'cmp': return compare(node.op, evalNode(node.l, data), evalNode(node.r, data))
  }
}

function truthy(v: unknown): boolean {
  return v === true || (typeof v !== 'boolean' && Boolean(v))
}

/** Parse + evaluate a `when` against the data object. Throws on a parse error. */
export function evaluateCondition(expr: string, data: Record<string, unknown>): boolean {
  const ast = new Parser(tokenize(expr)).parse()
  return truthy(evalNode(ast, data))
}

/** Validate a `when` expression compiles. Returns an error message, or null when valid. */
export function validateCondition(expr: string): string | null {
  try {
    new Parser(tokenize(expr)).parse()
    return null
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
}

/** Best-effort evaluate; a parse/runtime error counts as "not matched" (false). */
export function safeEvaluate(expr: string, data: Record<string, unknown>): boolean {
  try {
    return evaluateCondition(expr, data)
  } catch {
    return false
  }
}
