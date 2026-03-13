// Rewrite /beats, /beats/, and /beats/* (SPA routes) to /beats/index.html
// Static assets (/beats/static/..., .js, .css, etc.) keep their paths
function handler(event) {
    var request = event.request;
    var uri = request.uri;

    // Only rewrite /beats or /beats/* (not e.g. /beats-something)
    if (uri !== '/beats' && !uri.startsWith('/beats/')) {
        return request;
    }
    // Has a file extension? (static asset) — leave as-is
    if (uri.includes('.') && uri.lastIndexOf('.') > uri.lastIndexOf('/')) {
        return request;
    }
    // /beats, /beats/, or /beats/route → serve /beats/index.html
    request.uri = '/beats/index.html';
    return request;
}
