// Tells the user straight away when a chosen file cannot be uploaded.
// The server repeats these checks and has the final say.
(function () {

    const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    const IMAGE_EXTENSIONS = ["jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif"];
    const IMAGE_TYPES = ["image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/x-png", "image/webp", "image/gif"];

    function imageError(file) {
        const extension = file.name.includes(".") ? file.name.split(".").pop().toLowerCase() : "";

        if (!IMAGE_EXTENSIONS.includes(extension) || (file.type && !IMAGE_TYPES.includes(file.type))) {
            return "Please choose a JPEG, PNG, WebP or GIF image.";
        }

        if (file.size > MAX_IMAGE_BYTES) {
            return "This image is too large. The maximum size is 5 MB.";
        }

        return "";
    }

    function watchImageInput(input) {
        input.addEventListener("change", function () {
            const file = input.files[0];
            const error = file ? imageError(file) : "";

            if (error) {
                input.value = "";
                alert(error);
            }
        });
    }

    window.UploadCheck = Object.freeze({imageError, watchImageInput});
})();
