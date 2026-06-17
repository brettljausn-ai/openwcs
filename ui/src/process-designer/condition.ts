// Safe evaluator for the restricted expression grammar (spec §6 conditions + the compute step's
// value-returning expressions). This is deliberately NOT eval/Function: it is a hand-written
// recursive-descent parser + interpreter over a tiny grammar, so a definition (data that can come
// from the server / a non-developer designer) can never execute host code.
//
// Grammar (lowest -> highest precedence):
//   expr   := or
//   or     := and ( "or" and )*
//   and    := not ( "and" not )*
//   not    := "not" not | cmp
//   cmp    := add ( ("=="|"!="|"<"|"<="|">"|">=") add )?
//   add    := mul ( ("+"|"-") mul )*
//   mul    := unary ( ("*"|"/") unary )*
//   unary  := "-" unary | primary
//   primary:= "(" expr ")" | literal | ident
//   literal:= number | string ('...' or "...") | true | false | null
//   ident  := data-object variable name, may be dotted (e.g. sku.code)
//
// Operands resolve against the data object. Comparisons coerce sensibly (number vs number,
// boolean vs boolean, string vs string; mixed -> string compare); a comparison involving undefined
// is false. Arithmetic on a non-number yields undefined (NaN normalised) rather than throwing.
// Unknown variable = undefined. Any parse error throws; callers treat a failed condition as
// "not matched" (see flow walker / safeEvaluate).

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

// Comparison operators (matched before the single-char arithmetic operators so "<=" wins over "<").
const CMP_OPS = ['==', '!=', '<=', '>=', '<', '>']
const ARITH_OPS = ['+', '-', '*', '/']

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
    // comparison operator (multi-char first)
    const cmp = CMP_OPS.find((o) => src.startsWith(o, i))
    if (cmp) { toks.push({ t: 'op', v: cmp }); i += cmp.length; continue }
    // arithmetic operator
    if (ARITH_OPS.includes(c)) { toks.push({ t: 'op', v: c }); i++; continue }
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
  | { k: 'arith'; op: string; l: Node; r: Node }
  | { k: 'neg'; e: Node }
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
    if (this.pos !== this.toks.length) throw new Error('Trailing tokens in expression')
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
    const l = this.parseAdd()
    const p = this.peek()
    if (p?.t === 'op' && CMP_OPS.includes(p.v)) { this.next(); return { k: 'cmp', op: p.v, l, r: this.parseAdd() } }
    return l
  }
  private parseAdd(): Node {
    let l = this.parseMul()
    let p = this.peek()
    while (p?.t === 'op' && (p.v === '+' || p.v === '-')) {
      this.next()
      l = { k: 'arith', op: p.v, l, r: this.parseMul() }
      p = this.peek()
    }
    return l
  }
  private parseMul(): Node {
    let l = this.parseUnary()
    let p = this.peek()
    while (p?.t === 'op' && (p.v === '*' || p.v === '/')) {
      this.next()
      l = { k: 'arith', op: p.v, l, r: this.parseUnary() }
      p = this.peek()
    }
    return l
  }
  private parseUnary(): Node {
    const p = this.peek()
    if (p?.t === 'op' && p.v === '-') { this.next(); return { k: 'neg', e: this.parseUnary() } }
    return this.parsePrimary()
  }
  private parsePrimary(): Node {
    const tok = this.next()
    if (!tok) throw new Error('Unexpected end of expression')
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
      default: throw new Error(`Unexpected token in expression`)
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
  // A comparison involving undefined is always false (spec: comparisons with undefined are false).
  if (a === undefined || b === undefined) return false
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

/** Coerce an operand to a number for arithmetic; returns NaN for anything non-numeric (handled by
 *  the caller, which maps NaN -> undefined so a bad operand yields undefined rather than throwing). */
function toNum(v: unknown): number {
  if (typeof v === 'number') return v
  if (typeof v === 'boolean') return NaN // arithmetic on booleans is meaningless here
  if (v == null || v === '') return NaN
  const n = Number(v)
  return n
}

function arith(op: string, a: unknown, b: unknown): unknown {
  const an = toNum(a)
  const bn = toNum(b)
  if (Number.isNaN(an) || Number.isNaN(bn)) return undefined // non-number operand -> undefined
  let r: number
  switch (op) {
    case '+': r = an + bn; break
    case '-': r = an - bn; break
    case '*': r = an * bn; break
    case '/': r = bn === 0 ? NaN : an / bn; break
    default: return undefined
  }
  return Number.isNaN(r) ? undefined : r
}

function evalNode(node: Node, data: Record<string, unknown>): unknown {
  switch (node.k) {
    case 'lit': return node.v
    case 'var': return resolveVar(node.path, data)
    case 'not': return !truthy(evalNode(node.e, data))
    case 'and': return truthy(evalNode(node.l, data)) && truthy(evalNode(node.r, data))
    case 'or': return truthy(evalNode(node.l, data)) || truthy(evalNode(node.r, data))
    case 'cmp': return compare(node.op, evalNode(node.l, data), evalNode(node.r, data))
    case 'arith': return arith(node.op, evalNode(node.l, data), evalNode(node.r, data))
    case 'neg': {
      const v = toNum(evalNode(node.e, data))
      return Number.isNaN(v) ? undefined : -v
    }
  }
}

function truthy(v: unknown): boolean {
  return v === true || (typeof v !== 'boolean' && Boolean(v))
}

/** Parse + evaluate a value-returning expression against the data object (spec compute step).
 *  Returns the computed value (any type). Throws on a parse error. A bare identifier/literal is a
 *  valid expression. Identifiers resolve from the data object; undefined is treated as undefined. */
export function evaluateExpression(expr: string, data: Record<string, unknown>): unknown {
  const ast = new Parser(tokenize(expr)).parse()
  return evalNode(ast, data)
}

/** Validate an expression compiles (parse only). Returns an error message, or null when valid. */
export function validateExpression(expr: string): string | null {
  try {
    new Parser(tokenize(expr)).parse()
    return null
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
}

/** The data-object variable names an expression references (root of any dotted path). Empty when the
 *  expression does not tokenize. Used to check that every referenced variable is actually declared. */
export function expressionVars(expr: string): string[] {
  try {
    const out = new Set<string>()
    for (const tk of tokenize(expr)) {
      if (tk.t === 'ident' && typeof tk.v === 'string') out.add(tk.v.split('.')[0])
    }
    return [...out]
  } catch {
    return []
  }
}

/** Referenced variables that are NOT in the declared data object (so the designer can flag typos /
 *  variables that need adding). Returns [] when the expression does not parse (a syntax error is
 *  reported separately by validateExpression). */
export function unknownExpressionVars(expr: string, known: string[]): string[] {
  if (validateExpression(expr) != null) return []
  const declared = new Set(known)
  return expressionVars(expr).filter((v) => !declared.has(v))
}

/** Parse + evaluate a `when` against the data object, coercing the result to a boolean. Throws on a
 *  parse error. Delegates to the shared expression evaluator (so `qty + 1 > 0` is a valid condition). */
export function evaluateCondition(expr: string, data: Record<string, unknown>): boolean {
  return truthy(evaluateExpression(expr, data))
}

/** Validate a `when` expression compiles. Returns an error message, or null when valid. */
export function validateCondition(expr: string): string | null {
  return validateExpression(expr)
}

/** Best-effort evaluate; a parse/runtime error counts as "not matched" (false). */
export function safeEvaluate(expr: string, data: Record<string, unknown>): boolean {
  try {
    return evaluateCondition(expr, data)
  } catch {
    return false
  }
}
