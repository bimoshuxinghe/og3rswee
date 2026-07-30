import * as spider from '%s'

if (!globalThis.__JS_SPIDER__) {
    if (spider.__jsEvalReturn) {
        globalThis.req = http
        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()
    } else if (spider.default) {
        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default
    } else if (globalThis.__DRPY_RULE__ && globalThis.createDrpySpider) {
        globalThis.__JS_SPIDER__ = globalThis.createDrpySpider(globalThis.__DRPY_RULE__)
    }
}
