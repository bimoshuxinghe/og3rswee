import cheerio from './cheerio.min.js'

const load = cheerio.load || cheerio

function isBlank(value) {
    return value === undefined || value === null || value === ''
}

function normalize(value) {
    return isBlank(value) ? '' : String(value).trim()
}

function parseRule(rule) {
    if (isBlank(rule)) return []
    return String(rule).split('&&').map(item => item.trim()).filter(item => item.length > 0)
}

function createContext(input) {
    if (typeof input === 'function') {
        let $ = input
        return { $, current: $.root ? $.root() : $('body') }
    }
    let $ = load(typeof input === 'string' ? input : '')
    let current = typeof input === 'string' || isBlank(input) ? $.root() : $(input)
    return { $, current }
}

function select(input, parts) {
    let context = createContext(input)
    let $ = context.$
    let current = context.current
    for (let i = 0; i < parts.length; i++) {
        let part = parts[i]
        if (isOutput(part)) return current
        if (part === 'body') {
            current = $('body')
            continue
        }
        current = current.find(part)
    }
    return current
}

function isOutput(part) {
    let key = part.toLowerCase()
    return key === 'text' || key === 'html' || key === 'outerhtml' || key === 'innerhtml' || isAttributeOutput(part)
}

function isAttributeOutput(part) {
    let key = part.toLowerCase()
    return key.startsWith('attr(') || key.startsWith('@') || key.startsWith('data-') || [
        'href',
        'src',
        'style',
        'value',
        'alt',
        'title',
        'content',
        'data-src',
        'data-original',
        'data-lazy-src',
        'data-url'
    ].includes(key)
}

function getAttrName(part) {
    if (part.startsWith('@')) return part.substring(1)
    let match = part.match(/^attr\((.+)\)$/i)
    return match ? match[1].trim() : part
}

function pickOutput(input, parts) {
    if (parts.length === 0) return ''
    let last = parts[parts.length - 1]
    let context = createContext(input)
    let $ = context.$
    let target = context.current
    let output = isOutput(last)
    let selectors = output ? parts.slice(0, -1) : parts
    for (let i = 0; i < selectors.length; i++) {
        let part = selectors[i]
        if (part === 'body') target = $('body')
        else target = target.find(part)
    }
    if (!target || target.length === 0) return ''
    if (!output) return normalize(target.text())
    let key = last.toLowerCase()
    if (key === 'text') return normalize(target.text())
    if (key === 'html' || key === 'innerhtml') return normalize(target.html())
    if (key === 'outerhtml') return normalize($.html(target))
    return normalize(target.attr(getAttrName(last)))
}

function resolveUrl(url, base) {
    url = normalize(url)
    if (!url) return ''
    if (!base || /^(?:[a-z]+:)?\/\//i.test(url) || /^(?:data|blob|magnet|thunder|ftp):/i.test(url)) return url
    try {
        return new URL(url, base).toString()
    } catch (e) {
        if (url.startsWith('/')) {
            let match = String(base).match(/^([a-z]+:\/\/[^/]+)/i)
            return match ? match[1] + url : url
        }
        return String(base).replace(/\/[^/]*$/, '/') + url
    }
}

function ensure(name, value) {
    if (globalThis[name] === undefined) globalThis[name] = value
}

ensure('pq', input => createContext(input).$)
ensure('pdfa', (input, rule) => {
    let parts = parseRule(rule)
    return select(input, parts).toArray()
})
ensure('pdfh', (input, rule) => pickOutput(input, parseRule(rule)))
ensure('pd', (input, rule, base) => resolveUrl(globalThis.pdfh(input, rule), base || globalThis.MY_URL || globalThis.HOST))
ensure('pD', globalThis.pd)
ensure('pjfh', (input, rule) => globalThis.pdfh(input, rule))
ensure('pjfa', (input, rule) => globalThis.pdfa(input, rule))
ensure('joinUrl', globalThis.joinUrl || resolveUrl)
ensure('$js', {
    toString: value => typeof value === 'function' ? value.toString() : String(value ?? '')
})

const storage = {}
const pcUa = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
const mobileUa = 'Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36'

function normalizeHeader(headers = {}) {
    let result = {}
    Object.keys(headers || {}).forEach(key => {
        let value = headers[key]
        if (value === 'MOBILE_UA') value = mobileUa
        if (value === 'PC_UA') value = pcUa
        result[key] = value
    })
    return result
}

function applyImageReplace(rule, pic) {
    let value = normalize(pic)
    let replace = ruleValue(rule, '图片替换')
    if (!value || !replace || !replace.includes('=>')) return value
    let parts = replace.split('=>')
    return value.replace(parts[0], parts[1])
}

function ruleValue(rule, ...keys) {
    for (let i = 0; i < keys.length; i++) {
        let key = keys[i]
        if (rule && rule[key] !== undefined) return rule[key]
    }
    return undefined
}

function applyCdnDefendCookie(rule, html) {
    if (!html || !html.includes('cdndefend')) return false
    let match = html.match(/a0_0x2a54\s*=\s*\['([^']+)'/)
    if (!match || !globalThis.cdnDefendX) return false
    let cookie = globalThis.cdnDefendX(match[1])
    if (!cookie) return false
    rule.headers = rule.headers || {}
    rule.headers.cookie = cookie
    storage['cdndefend:' + rule.host] = cookie
    return true
}

function requestText(url, rule, options = {}) {
    rule.headers = rule.headers || {}
    if (!rule.headers.cookie && !rule.headers.Cookie && storage['cdndefend:' + rule.host]) {
        rule.headers.cookie = storage['cdndefend:' + rule.host]
    }
    let requestUrl = resolveUrl(url, rule.host)
    let requestOptions = Object.assign({
        headers: normalizeHeader(rule.headers || {})
    }, options)
    let response = req(requestUrl, requestOptions)
    let content = response && response.content ? response.content : ''
    if (applyCdnDefendCookie(rule, content)) {
        response = req(requestUrl, Object.assign({}, requestOptions, { headers: normalizeHeader(rule.headers || {}) }))
        content = response && response.content ? response.content : ''
    }
    return content
}

function splitRule(value) {
    return normalize(value).split(';')
}

function extractValue(element, ruleText, rule) {
    if (!ruleText || ruleText === '*') return ''
    return normalize(globalThis.pdfh(element, ruleText)).replace(rule.host, '')
}

function parseList(html, ruleText, rule, fallback) {
    let parts = splitRule(ruleText)
    let listRule = parts[0]
    let nameRule = parts[1] === '*' ? fallback.name : parts[1]
    let picRule = parts[2] === '*' ? fallback.pic : parts[2]
    let remarkRule = parts[3] === '*' ? fallback.remark : parts[3]
    let idRule = parts[4] === '*' ? fallback.id : parts[4]
    return globalThis.pdfa(html, listRule).map(element => {
        let vodId = resolveUrl(extractValue(element, idRule, rule), rule.host)
        let vodName = extractValue(element, nameRule, rule)
        let vodPic = applyImageReplace(rule, resolveUrl(extractValue(element, picRule, rule), rule.host))
        let vodRemarks = extractValue(element, remarkRule, rule)
        return {
            vod_id: vodId,
            vod_name: vodName,
            vod_pic: vodPic,
            vod_remarks: vodRemarks
        }
    }).filter(item => item.vod_id && item.vod_name)
}

function parseClasses(html, rule) {
    let parts = splitRule(rule.class_parse)
    let exclude = new RegExp(rule.cate_exclude || '$.^')
    return globalThis.pdfa(html, parts[0]).map(element => {
        let typeName = extractValue(element, parts[1], rule)
        let typeId = extractValue(element, parts[2], rule)
        if (parts[3]) {
            let match = typeId.match(new RegExp(parts[3]))
            typeId = match && match[1] ? match[1] : typeId
        }
        return { type_id: typeId, type_name: typeName }
    }).filter(item => item.type_id && item.type_name && !exclude.test(item.type_name))
}

function runRuleScript(script, rule) {
    if (!script) return
    globalThis.rule = rule
    globalThis.request = url => requestText(url, rule)
    globalThis.getItem = key => storage[key] || ''
    globalThis.setItem = (key, value) => {
        storage[key] = value
    }
    let func = Function('return (' + script + ')')()
    if (typeof func === 'function') func()
}

function isHeavyPreprocess(script) {
    let text = normalize(script)
    return /CryptoJS\.SHA1|while\s*\([^)]*1000000|for\s*\([^)]*1000000/.test(text)
}

function runPreprocess(rule) {
    let script = ruleValue(rule, '预处理')
    if (!script || isHeavyPreprocess(script)) return
    runRuleScript(script, rule)
}

function buildUrl(rule, tid, pg) {
    return resolveUrl(rule.url.replace('fyclass', tid).replace('fypage', pg).replace('fyfilter', ''), rule.host)
}

function buildSearchUrl(rule, key, pg) {
    return resolveUrl(rule.searchUrl.replace('**', encodeURIComponent(key)).replace('fypage', pg), rule.host)
}

function parseTabs(html, rule) {
    let exclude = new RegExp(rule.tab_exclude || '$.^')
    return globalThis.pdfa(html, rule['二级'].tabs).map(element => globalThis.pdfh(element, 'Text')).filter(item => item && !exclude.test(item))
}

function parseEpisodes(html, rule, index) {
    let listRule = rule['二级'].lists.replace('#id', index)
    return globalThis.pdfa(html, listRule).map(element => {
        let name = globalThis.pdfh(element, 'Text')
        let href = resolveUrl(globalThis.pdfh(element, 'href'), rule.host)
        return name && href ? name + '$' + href : ''
    }).filter(Boolean).join('#')
}

function createDrpySpider(rule) {
    let fallback = {
        name: '.v-item-title:eq(1)&&Text',
        pic: 'img:last-of-type&&data-original',
        remark: '.v-item-bottom&&span&&Text',
        id: 'a&&href'
    }
    return {
        init() {
            runPreprocess(rule)
        },
        home(filter) {
            let html = requestText(rule.host, rule)
            return JSON.stringify({ class: parseClasses(html, rule), filters: {} })
        },
        homeVod() {
            let html = requestText(rule.host, rule)
            return JSON.stringify({ list: parseList(html, ruleValue(rule, '推荐') || ruleValue(rule, '一级'), rule, fallback) })
        },
        category(tid, pg) {
            let html = requestText(buildUrl(rule, tid, pg || '1'), rule)
            return JSON.stringify({ page: Number(pg || 1), list: parseList(html, ruleValue(rule, '一级'), rule, fallback) })
        },
        detail(id) {
            let html = requestText(id, rule)
            let detail = ruleValue(rule, '二级') || {}
            let title = splitRule(detail.title)
            let desc = splitRule(detail.desc)
            let tabs = parseTabs(html, rule)
            let vod = {
                vod_id: id,
                vod_name: extractValue(html, title[0], rule),
                vod_pic: applyImageReplace(rule, resolveUrl(extractValue(html, detail.img, rule), rule.host)),
                type_name: extractValue(html, title[1], rule),
                vod_remarks: extractValue(html, desc[0], rule),
                vod_year: extractValue(html, desc[1], rule),
                vod_area: extractValue(html, desc[2], rule),
                vod_actor: extractValue(html, desc[3], rule),
                vod_director: extractValue(html, desc[4], rule),
                vod_content: extractValue(html, detail.content, rule),
                vod_play_from: tabs.join('$$$'),
                vod_play_url: tabs.map((_, index) => parseEpisodes(html, rule, index)).join('$$$')
            }
            return JSON.stringify({ list: [vod] })
        },
        search(key, quick, pg) {
            let html = requestText(buildSearchUrl(rule, key, pg || '1'), rule)
            return JSON.stringify({ list: parseList(html, ruleValue(rule, '搜索'), rule, fallback) })
        },
        play(flag, id) {
            if (!rule.lazy) return JSON.stringify({ parse: rule.play_parse ? 1 : 0, url: id })
            globalThis.input = id
            runRuleScript(rule.lazy, rule)
            return JSON.stringify(typeof globalThis.input === 'object' ? globalThis.input : { parse: 0, url: globalThis.input })
        },
        live() {
            return ''
        },
        proxy() {
            return [404, 'text/plain', '']
        },
        action() {
            return ''
        },
        destroy() {},
        sniffer() {
            return false
        },
        isVideo() {
            return false
        }
    }
}

ensure('createDrpySpider', createDrpySpider)
