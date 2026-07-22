/*
* vue config
* */
const fs = require('fs-extra')
const buildVersion = process.env.BUILD_VERSION || new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)
module.exports = {
    publicPath: '/',
    outputDir: "../src/main/resources/static/",
    devServer: {
        open: true,
        port: 8020,
        overlay: {
            warnings: false,
            errors: true
        },
        https: false,
        openPage: '#/?path=single',
        // 添加 proxy 配置
        proxy: {
            '/api': {
                target: 'http://localhost:9003', // 替换为你的后端服务器地址
                changeOrigin: true,
                pathRewrite: {
                    '^/api': '/api' // 如果你的后端接口不包含 /api 前缀，可以通过这个设置去掉
                }
            }
        }
    },
    configureWebpack: config => {
        if (process.env.NODE_ENV === 'production') {
            config.output = Object.assign({}, config.output, {
                filename: `js/[name].${buildVersion}.js`,
                chunkFilename: `js/[name].${buildVersion}.js`,
            })
        }
        if (process.env.NODE_ENV === 'development') {
            config.devtool = 'eval-source-map'
        }
    },
}
