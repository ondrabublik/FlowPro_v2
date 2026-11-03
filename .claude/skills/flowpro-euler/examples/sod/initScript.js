// Sod: rho, rho*u, rho*v, E   (p = 1, rho = 1 on the left)
function init(x) {
    var k = 1.4;
    if (x[0] < 0.5) return [1, 0, 0, 1 / (k - 1)];
    return [0.125, 0, 0, 0.1 / (k - 1)];
}
