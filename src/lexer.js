// http://lisperator.net/pltut/parser/

export class Stream {

  constructor (input) {
    this.input = input
    this.pos   = 0
    this.line  = 1
    this.col   = 0
  }

  * next () {
    let ch = this.input.charAt(this.pos++)

    if (ch === '\n') {
      this.line++
      this.col = 0
    } else {
      this.col++
    }

    yield ch
  }

  cursor () {
    return this.input.charAt(this.pos)
  }

  eof () {
    return this.cursor() === null //this.cursor() === ''
  }

  error (msg) {
    throw new Error(`${msg} (${this.line}: ${this.col})`)
  }

}

export class TokenStream extends Stream {

  constructor (input) {
    super(input)

    this.current = null
  }

  cursor() {
    return current || (this.current = this.readNext())
  }

  next() {
    const token = this.current

    this.current = null

    return token || this.readNext()
  }

  eof() {
    return this.cursor() === null
  }

  isKeyword(str) {
    return ~('Loop Times Forever'.indexOf(str))
  }

  isIdent(ch) {
    return this.isIdentStart(ch) || /[a-zA-Z0-9]/.test(ch)
  }

  isIdentStart(ch) {
    return ch === ':' ||  ch === '~'
  }

  isOperator(ch) {
    return ch === '='
  }

  isPunc(ch) {
    return ~('[](),'.indexOf(ch))
  }

  isNumber(ch) {
    return /[0-9]/i.test(ch)
  }

  isWhitespace(ch) {
    return ~(' \t\n'.indexOf(ch))
  }

  isComment() {
    return ch === '/'
  }

  isMeta() {

  }

  isSection() {

  }

  isBeat() {

  }

  isChord() {

  }

  isScale() {

  }

  isColor() {

  }

  * readWhile(predicate) {
    while (!this.input.eof() && predicate(this.input.cursor())) {
      yield input.next()
    }
  }

  readNext() {
    this.readWhile(this.isWhitespace)

    if (this.input.eof()) return null

    const ch   = this.input.cursor()
    const pipe = ['meta', 'comment', 'ident', 'section', 'beat', 'chord', 'scale', 'color']

    for (stage in pipe) {
      const name = stage.toUppercase()
      const is   = this[`is${name}`]
      const read = this[`read${name}`]

      if (is(ch)) {
        return read()
      }
    }

    this.input.error(`invalid character: ${ch}`)
  }

  readIdent() {
    const ident = [... this.readWhile(this.isIdent)]

    return {
      type  : this.isKeyword(ident) ? 'kw' : 'var',
      value : ident
    }
  }

  readMeta() {

  }

  readComment() {

  }

  readSection() {

  }

  readBeat() {

  }

  readChord() {

  }

  readScale() {

  }

  readColor() {

  }

  skipComment() {

  }

}

